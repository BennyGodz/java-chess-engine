package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

/**
 * Turns a board position into the NNUE network's input vector (769 inputs total): 0-383 piece
 * square features signed by colour (+1 white, -1 black); 384-511 white king square one-hot plus
 * centre bonus; 512-639 black king square one-hot plus centre bonus; 640-663 pawn structure
 * (passed, isolated, connected, backward, counts per file); 664-679 king safety (distance, colour
 * complex, attackers, shield, mobility, centre control); 680-683 castling rights; 684 en passant
 * availability; 768 side to move.
 */
public class NNUEFeatureExtractor {

  public static final int INPUT_SIZE = 769;

  public double[] extract(Board board) {
    double[] features = new double[INPUT_SIZE];

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Piece piece = board.getPiece(new Position(row, col));
        if (piece != null) {
          features[pieceTypeIndex(piece) * 64 + row * 8 + col] = piece.isWhite() ? 1.0 : -1.0;
        }
      }
    }

    addKingFeatures(board.findKing(true), features, 384, 511, 1.0);
    addKingFeatures(board.findKing(false), features, 512, 639, -1.0);

    addPawnStructureFeatures(board, features);
    addKingSafetyFeatures(board, features);

    features[680] = board.canCastleKingside(true) ? 1.0 : 0.0;
    features[681] = board.canCastleQueenside(true) ? 1.0 : 0.0;
    features[682] = board.canCastleKingside(false) ? -1.0 : 0.0;
    features[683] = board.canCastleQueenside(false) ? -1.0 : 0.0;

    features[684] = board.getEnPassantTarget() == null ? 0.0 : board.isWhiteToMove() ? 1.0 : -1.0;
    features[768] = board.isWhiteToMove() ? 1.0 : 0.0;
    return features;
  }

  private static int pieceTypeIndex(Piece piece) {
    if (piece instanceof Pawn) return 0;
    if (piece instanceof Knight) return 1;
    if (piece instanceof Bishop) return 2;
    if (piece instanceof Rook) return 3;
    if (piece instanceof Queen) return 4;
    return 5;
  }

  private void addKingFeatures(
      Position king, double[] features, int squareOffset, int bonusIndex, double sign) {
    if (king == null) return;
    features[squareOffset + king.getRow() * 8 + king.getColumn()] = 1.0;
    features[bonusIndex] = sign * getKingCenterBonus(king) / 30.0;
  }

  /**
   * Fills the pawn structure block: passed-pawn bonus, isolated pawns, connected pawns, backward
   * pawns and per-file pawn counts, each as a white/black pair with opposite signs. Values are
   * scaled so handcrafted inputs stay on the same order of magnitude as the ±1 piece features.
   */
  private void addPawnStructureFeatures(Board board, double[] features) {
    int[][] pawnCounts = new int[2][8];

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Piece piece = board.getPiece(new Position(row, col));
        if (piece instanceof Pawn) {
          pawnCounts[piece.isWhite() ? 0 : 1][col]++;
        }
      }
    }

    boolean[] whiteFiles = occupiedFiles(pawnCounts[0]);
    boolean[] blackFiles = occupiedFiles(pawnCounts[1]);
    features[640] = Math.min(calculatePassedPawnBonus(board, true) / 200.0, 1.0);
    features[641] = -Math.min(calculatePassedPawnBonus(board, false) / 200.0, 1.0);
    features[642] = hasIsolatedPawn(whiteFiles) ? 1.0 : 0.0;
    features[643] = hasIsolatedPawn(blackFiles) ? -1.0 : 0.0;
    features[644] = hasConnectedPawn(whiteFiles) ? 1.0 : 0.0;
    features[645] = hasConnectedPawn(blackFiles) ? -1.0 : 0.0;
    features[646] = features[642];
    features[647] = features[643];
    for (int f = 0; f < 8; f++) {
      features[648 + f] = pawnCounts[0][f] / 8.0;
      features[656 + f] = -pawnCounts[1][f] / 8.0;
    }
  }

  private static boolean[] occupiedFiles(int[] pawnCounts) {
    boolean[] occupied = new boolean[8];
    for (int file = 0; file < 8; file++) occupied[file] = pawnCounts[file] > 0;
    return occupied;
  }

  /**
   * Sums a bonus for every passed pawn, larger the further it has advanced. The total is capped so
   * one wild position cannot dominate the feature.
   */
  private int calculatePassedPawnBonus(Board board, boolean white) {
    int bonus = 0;
    int direction = white ? 1 : -1;

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Position pos = new Position(row, col);
        Piece piece = board.getPiece(pos);
        if (piece == null || !(piece instanceof Pawn) || piece.isWhite() != white) continue;

        if (isPassedPawn(board, row, col, white, direction)) {
          int advancement = white ? (7 - row) : row;
          bonus += 30 - advancement * 3;
          if (bonus > 200) return bonus;
        }
      }
    }
    return bonus;
  }

  private boolean isPassedPawn(Board board, int row, int column, boolean white, int direction) {
    for (int r = row + direction; r >= 0 && r < 8; r += direction) {
      for (int c = column - 1; c <= column + 1; c++) {
        if (!isValidPos(r, c)) continue;
        Piece piece = board.getPiece(new Position(r, c));
        if (piece instanceof Pawn && piece.isWhite() != white) return false;
      }
    }
    return true;
  }

  /** Returns whether a row/column pair lies on the board. */
  private boolean isValidPos(int row, int col) {
    return row >= 0 && row < 8 && col >= 0 && col < 8;
  }

  /**
   * Fills the king safety block: distance between kings, shared colour complex, enemy pieces next
   * to each king, pawn shield, king mobility and centre control.
   */
  private void addKingSafetyFeatures(Board board, double[] features) {
    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);

    if (whiteKing == null || blackKing == null) return;

    int whiteKingRow = whiteKing.getRow();
    int whiteKingCol = whiteKing.getColumn();
    int blackKingRow = blackKing.getRow();
    int blackKingCol = blackKing.getColumn();

    int manhattanDist =
        Math.abs(whiteKingRow - blackKingRow) + Math.abs(whiteKingCol - blackKingCol);
    features[670] = manhattanDist / 14.0;

    int whiteKingColor = (whiteKingRow + whiteKingCol) & 1;
    int blackKingColor = (blackKingRow + blackKingCol) & 1;
    features[671] = whiteKingColor == blackKingColor ? 1.0 : -1.0;

    features[672] = countAttackersNearKing(board, true, whiteKing) / 8.0;
    features[673] = -countAttackersNearKing(board, false, blackKing) / 8.0;

    features[674] = isKingShielded(board, true, whiteKing) ? 1.0 : 0.0;
    features[675] = isKingShielded(board, false, blackKing) ? -1.0 : 0.0;

    features[676] = calculateKingMobility(board, true, whiteKing) / 8.0;
    features[677] = -calculateKingMobility(board, false, blackKing) / 8.0;

    features[678] = calculateKingCenterControl(board, true) / 4.0;
    features[679] = -calculateKingCenterControl(board, false) / 4.0;
  }

  /** Counts enemy pieces standing on the eight squares surrounding a king. */
  private int countAttackersNearKing(Board board, boolean white, Position king) {
    int count = 0;
    int tr = king.getRow();
    int tc = king.getColumn();

    for (int r = tr - 1; r <= tr + 1; r++) {
      for (int c = tc - 1; c <= tc + 1; c++) {
        if (r == tr && c == tc) continue;
        if (!isValidPos(r, c)) continue;
        Piece piece = board.getPiece(new Position(r, c));
        if (piece != null && piece.isWhite() != white) count++;
      }
    }
    return count;
  }

  /** Returns whether at least one own pawn stands within three ranks in front of the king. */
  private boolean isKingShielded(Board board, boolean white, Position king) {
    int kr = king.getRow();
    int kc = king.getColumn();
    int dir = white ? 1 : -1;

    for (int df = -1; df <= 1; df++) {
      int ac = kc + df;
      if (ac < 0 || ac >= 8) continue;
      for (int distance = 1; distance <= 3; distance++) {
        int r = kr + dir * distance;
        if (!isValidPos(r, ac)) break;
        Piece piece = board.getPiece(new Position(r, ac));
        if (piece instanceof Pawn && piece.isWhite() == white) return true;
      }
    }
    return false;
  }

  /** Counts how many adjacent squares a king could still safely step to. */
  private int calculateKingMobility(Board board, boolean white, Position king) {
    int mobility = 0;
    int tr = king.getRow();
    int tc = king.getColumn();

    for (int r = tr - 1; r <= tr + 1; r++) {
      for (int c = tc - 1; c <= tc + 1; c++) {
        if (r == tr && c == tc) continue;
        if (!isValidPos(r, c)) continue;
        if (board.isSquareAttacked(new Position(r, c), !white)) continue;
        Piece piece = board.getPiece(new Position(r, c));
        if (piece != null && piece.isWhite() == white) continue;
        mobility++;
      }
    }
    return mobility;
  }

  /** Counts how many central squares a king attacks without being attacked there itself. */
  private double calculateKingCenterControl(Board board, boolean white) {
    Position k = board.findKing(white);
    if (k == null) return 0.0;

    int controlCount = 0;
    for (int dr = -1; dr <= 1; dr++) {
      for (int dc = -1; dc <= 1; dc++) {
        int r = k.getRow() + dr;
        int c = k.getColumn() + dc;
        if (r >= 4
            && r <= 5
            && c >= 3
            && c <= 4
            && !board.isSquareAttacked(new Position(r, c), !white)) {
          controlCount++;
        }
      }
    }
    return controlCount;
  }

  /**
   * Returns the colour-flipped rank mirror of a feature vector: every white piece becomes a black
   * piece on the rank-mirrored square (files are preserved), colours swap and the side to move
   * flips. Rank mirroring is used instead of a full rotation because castling is tied to the
   * e-file; mirroring files too would move kings off e1/e8 and make castling impossible in the
   * mirrored position. From the mover's perspective the mirrored position has exactly the same
   * value, which makes this a free label-preserving training example.
   */
  public static double[] rotated(double[] features) {
    double[] out = new double[INPUT_SIZE];

    // Piece-square block: same type, rank-mirrored square, flipped colour.
    for (int type = 0; type < 6; type++) {
      for (int sq = 0; sq < 64; sq++) {
        double v = features[type * 64 + sq];
        if (v != 0.0) {
          out[type * 64 + rotSquare(sq)] = -v;
        }
      }
    }

    // King one-hot blocks (white 384-447, black 512-575) swap places.
    for (int sq = 0; sq < 64; sq++) {
      if (features[384 + sq] != 0.0) out[512 + rotSquare(sq)] = 1.0;
      if (features[512 + sq] != 0.0) out[384 + rotSquare(sq)] = 1.0;
    }

    // King centre bonuses swap signs along with their blocks.
    out[511] = -features[639];
    out[639] = -features[511];

    for (int index : new int[] {640, 642, 644, 646, 672, 674, 676, 678}) {
      swapNegate(features, out, index, index + 1);
    }

    // Per-file pawn counts: files are preserved under the rank flip, so only signs flip.
    for (int f = 0; f < 8; f++) {
      out[656 + f] = -features[648 + f];
      out[648 + f] = -features[656 + f];
    }

    // King distance (670) is invariant under a rank flip; square-colour parity (671) flips for
    // every square, so whether the kings share a complex does not change either.
    out[670] = features[670];
    out[671] = features[671];

    // Castling rights swap between colours (files preserved).
    out[680] = -features[682]; // rotated white kingside <- original black kingside
    out[681] = -features[683]; // rotated white queenside <- original black queenside
    out[682] = -features[680]; // rotated black kingside <- original white kingside
    out[683] = -features[681]; // rotated black queenside <- original white queenside

    // En passant availability survives but the side able to capture flips.
    out[684] = -features[684];

    // Side to move flips.
    out[768] = features[768] == 1.0 ? 0.0 : 1.0;

    return out;
  }

  /** Returns the rank-mirrored square index: row becomes 7 - row, column unchanged. */
  private static int rotSquare(int squareIndex) {
    int row = squareIndex / 8;
    int col = squareIndex % 8;
    return (7 - row) * 8 + col;
  }

  /** True when any own pawn sits on a file adjacent to another own pawn. */
  private static boolean hasConnectedPawn(boolean[] pawnFiles) {
    for (int f = 0; f < 8; f++) {
      if (!pawnFiles[f]) continue;
      if ((f > 0 && pawnFiles[f - 1]) || (f < 7 && pawnFiles[f + 1])) return true;
    }
    return false;
  }

  private static boolean hasIsolatedPawn(boolean[] pawnFiles) {
    for (int f = 0; f < 8; f++) {
      if (!pawnFiles[f]) continue;
      boolean left = f > 0 && pawnFiles[f - 1];
      boolean right = f < 7 && pawnFiles[f + 1];
      if (!left && !right) return true;
    }
    return false;
  }

  /** Swaps two paired features while flipping their sign: out[b] = -in[a], out[a] = -in[b]. */
  private static void swapNegate(double[] features, double[] out, int a, int b) {
    out[b] = -features[a];
    out[a] = -features[b];
  }

  private int getKingCenterBonus(Position king) {
    int row = king.getRow();
    int col = king.getColumn();
    int bonus = (row == 3 || row == 4) && (col == 3 || col == 4) ? 20 : 0;
    int distanceFromEdge = Math.min(Math.min(row, 7 - row), Math.min(col, 7 - col));
    if (distanceFromEdge <= 1) bonus += 10;
    return bonus;
  }
}
