package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

/**
 * Turns a board position into the NNUE network's input vector.
 *
 * <p>Feature layout (769 inputs total):
 *
 * <ul>
 *   <li>0-383: piece-square features, signed by colour (+1 white, -1 black)
 *   <li>384-511: white king square one-hot plus centre bonus
 *   <li>512-639: black king square one-hot plus centre bonus
 *   <li>640-663: pawn structure (passed, isolated, connected, backward, counts per file)
 *   <li>664-679: king safety (distance, colour complex, attackers, shield, mobility, centre control)
 *   <li>680-683: castling rights
 *   <li>684: en passant availability
 *   <li>768: side to move
 * </ul>
 */
public class NNUEFeatureExtractor {

  /** Total input size of the network. */
  public static final int INPUT_SIZE = 769;

  /**
   * Extracts all features from a board into a fresh vector. White features are positive, black
   * features negative.
   */
  public double[] extract(Board board) {
    double[] features = new double[INPUT_SIZE];

    // Piece-square block: one slot per piece type and square, +1 for White, -1 for Black,
    // which lets the network distinguish colour through the sign alone.
    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Position pos = new Position(row, col);
        Piece piece = board.getPiece(pos);
        if (piece != null) {
          int squareIndex = row * 8 + col;
          int pieceTypeIndex;
          if (piece instanceof Pawn) pieceTypeIndex = 0;
          else if (piece instanceof Knight) pieceTypeIndex = 1;
          else if (piece instanceof Bishop) pieceTypeIndex = 2;
          else if (piece instanceof Rook) pieceTypeIndex = 3;
          else if (piece instanceof Queen) pieceTypeIndex = 4;
          else if (piece instanceof King) pieceTypeIndex = 5;
          else continue;

          int featureIndex = pieceTypeIndex * 64 + squareIndex;
          if (piece.isWhite()) {
            features[featureIndex] = 1.0;
          } else {
            features[featureIndex] = -1.0;
          }
        }
      }
    }

    // White king square (one-hot) and its small centre-control bonus.
    Position whiteKing = board.findKing(true);
    if (whiteKing != null) {
      int squareIndex = whiteKing.getRow() * 8 + whiteKing.getColumn();
      if (squareIndex >= 0 && squareIndex <= 63) {
        features[384 + squareIndex] = 1.0;
      }
      int centerBonus = getKingCenterBonus(whiteKing);
      if (centerBonus > 0) {
        features[511] = centerBonus / 30.0;
      }
    }

    // Black king square (one-hot) and its negated centre-control bonus.
    Position blackKing = board.findKing(false);
    if (blackKing != null) {
      int squareIndex = blackKing.getRow() * 8 + blackKing.getColumn();
      if (squareIndex >= 0 && squareIndex <= 63) {
        features[512 + squareIndex] = 1.0;
      }
      int centerBonus = getKingCenterBonus(blackKing);
      if (centerBonus > 0) {
        features[639] = -centerBonus / 30.0;
      }
    }

    addPawnStructureFeatures(board, features);

    addKingSafetyFeatures(board, features);

    // Castling rights: positive for White, negative for Black.
    features[680] = board.canCastleKingside(true) ? 1.0 : 0.0;
    features[681] = board.canCastleQueenside(true) ? 1.0 : 0.0;
    features[682] = board.canCastleKingside(false) ? -1.0 : 0.0;
    features[683] = board.canCastleQueenside(false) ? -1.0 : 0.0;

    // En passant availability: +1 when White may capture, -1 when Black may.
    features[684] = board.getEnPassantTarget() != null ? 1.0 : 0.0;
    if (board.getEnPassantTarget() != null && !board.isWhiteToMove()) {
      features[684] = -1.0;
    }

    // Side to move.
    features[768] = board.isWhiteToMove() ? 1.0 : 0.0;

    return features;
  }

  /**
   * Fills the pawn structure block: passed-pawn bonus, isolated pawns, connected pawns, backward
   * pawns and per-file pawn counts, each as a white/black pair with opposite signs. Values are
   * scaled so handcrafted inputs stay on the same order of magnitude as the ±1 piece features.
   */
  private void addPawnStructureFeatures(Board board, double[] features) {
    boolean[] whitePawnFiles = new boolean[8];
    boolean[] blackPawnFiles = new boolean[8];
    int[] whitePawnCountPerFile = new int[8];
    int[] blackPawnCountPerFile = new int[8];

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Position pos = new Position(row, col);
        Piece piece = board.getPiece(pos);
        if (piece == null) continue;

        if (piece instanceof Pawn) {
          if (piece.isWhite()) {
            whitePawnFiles[col] = true;
            whitePawnCountPerFile[col]++;
          } else {
            blackPawnFiles[col] = true;
            blackPawnCountPerFile[col]++;
          }
        }
      }
    }

    // Passed pawns.
    int whitePassedBonus = calculatePassedPawnBonus(board, true);
    int blackPassedBonus = calculatePassedPawnBonus(board, false);
    features[640] = Math.min(whitePassedBonus / 200.0, 1.0);
    features[641] = -Math.min(blackPassedBonus / 200.0, 1.0);

    // Isolated pawns.
    boolean whiteHasIsolated = false;
    boolean blackHasIsolated = false;

    for (int f = 0; f < 8; f++) {
      boolean whiteAdjacent = (f > 0 && whitePawnFiles[f - 1]) || (f < 7 && whitePawnFiles[f + 1]);
      boolean blackAdjacent = (f > 0 && blackPawnFiles[f - 1]) || (f < 7 && blackPawnFiles[f + 1]);
      if (whitePawnFiles[f] && !whiteAdjacent) whiteHasIsolated = true;
      if (blackPawnFiles[f] && !blackAdjacent) blackHasIsolated = true;
    }

    features[642] = whiteHasIsolated ? 1.0 : 0.0;
    features[643] = blackHasIsolated ? -1.0 : 0.0;

    // Connected pawn chains, evaluated independently per colour.
    boolean whiteHasConnected = hasConnectedPawn(whitePawnFiles);
    boolean blackHasConnected = hasConnectedPawn(blackPawnFiles);

    features[644] = whiteHasConnected ? 1.0 : 0.0;
    features[645] = blackHasConnected ? -1.0 : 0.0;

    // Backward pawns, evaluated independently per colour.
    boolean whiteHasBackward = hasBackwardPawn(whitePawnFiles);
    boolean blackHasBackward = hasBackwardPawn(blackPawnFiles);

    features[646] = whiteHasBackward ? 1.0 : 0.0;
    features[647] = blackHasBackward ? -1.0 : 0.0;

    // Pawn counts per file.
    for (int f = 0; f < 8; f++) {
      features[648 + f] = whitePawnCountPerFile[f] / 8.0;
      features[656 + f] = -blackPawnCountPerFile[f] / 8.0;
    }
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

        boolean isPassed = true;
        // Scan every square ahead on this file and both neighbouring files for enemy pawns.
        for (int r = row + direction; r >= 0 && r < 8; r += direction) {
          if (isValidPos(r, col)) {
            Piece checking = board.getPiece(new Position(r, col));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) {
              isPassed = false;
              break;
            }
          }
          if (isValidPos(r, col - 1) && col - 1 >= 0) {
            Piece checking = board.getPiece(new Position(r, col - 1));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) {
              isPassed = false;
              break;
            }
          }
          if (isValidPos(r, col + 1) && col + 1 < 8) {
            Piece checking = board.getPiece(new Position(r, col + 1));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) {
              isPassed = false;
              break;
            }
          }
          if (!isPassed) break;
        }

        if (isPassed) {
          int advancement = white ? (7 - row) : row;
          bonus += 30 - advancement * 3;
          if (bonus > 200) break;
        }
      }
      if (bonus > 200) break;
    }
    return bonus;
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

    if (whiteKing == null || blackKing == null) {
      return;
    }

    int whiteKingRow = whiteKing.getRow();
    int whiteKingCol = whiteKing.getColumn();
    int blackKingRow = blackKing.getRow();
    int blackKingCol = blackKing.getColumn();

    // Distance between the kings, normalized.
    int manhattanDist =
        Math.abs(whiteKingRow - blackKingRow) + Math.abs(whiteKingCol - blackKingCol);
    features[670] = manhattanDist / 14.0;

    // Same colour complex (+1) or opposite (-1).
    int whiteKingColor = (whiteKingRow + whiteKingCol) & 1;
    int blackKingColor = (blackKingRow + blackKingCol) & 1;
    features[671] = whiteKingColor == blackKingColor ? 1.0 : -1.0;

    // Enemy pieces adjacent to each king.
    features[672] = countAttackersNearKing(board, true, whiteKing) / 8.0;
    features[673] = -countAttackersNearKing(board, false, blackKing) / 8.0;

    // Pawn shield in front of each king.
    features[674] = isKingShielded(board, true, whiteKing) ? 1.0 : 0.0;
    features[675] = isKingShielded(board, false, blackKing) ? -1.0 : 0.0;

    // Safe squares the kings can move to.
    features[676] = calculateKingMobility(board, true, whiteKing) / 8.0;
    features[677] = -calculateKingMobility(board, false, blackKing) / 8.0;

    // Control of the four central squares around each king.
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
      for (int r = kr + dir; r >= 0 && r < 8; r += dir) {
        if (!isValidPos(r, ac)) continue;
        Piece piece = board.getPiece(new Position(r, ac));
        if (piece != null && piece instanceof Pawn && piece.isWhite() == white) {
          int rankDist = Math.abs(r - kr);
          if (rankDist >= 1 && rankDist <= 3) return true;
        }
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
        if (r >= 4 && r <= 5 && c >= 3 && c <= 4) {
          if (isValidPos(r, c) && !board.isSquareAttacked(new Position(r, c), !white)) {
            controlCount++;
          }
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

    // Pawn structure pairs swap and flip sign.
    swapNegate(features, out, 640, 641); // passed pawns
    swapNegate(features, out, 642, 643); // isolated pawns
    swapNegate(features, out, 644, 645); // connected pawns
    swapNegate(features, out, 646, 647); // backward pawns

    // Per-file pawn counts: files are preserved under the rank flip, so only signs flip.
    for (int f = 0; f < 8; f++) {
      out[656 + f] = -features[648 + f];
      out[648 + f] = -features[656 + f];
    }

    // King distance (670) is invariant under a rank flip; square-colour parity (671) flips for
    // every square, so whether the kings share a complex does not change either.
    out[670] = features[670];
    out[671] = features[671];

    // King safety pairs swap and flip sign.
    swapNegate(features, out, 672, 673); // attackers near king
    swapNegate(features, out, 674, 675); // pawn shield
    swapNegate(features, out, 676, 677); // mobility
    swapNegate(features, out, 678, 679); // centre control

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

  /** True when any own pawn sits on a file with no friendly pawn on either neighbouring file. */
  private static boolean hasBackwardPawn(boolean[] pawnFiles) {
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

  /** Returns a centre bonus for a king's square (20 on the centre, plus 10 near the edge). */
  private int getKingCenterBonus(Position king) {
    int row = king.getRow();
    int col = king.getColumn();
    int[][] centerSquares = {{3, 3}, {3, 4}, {4, 3}, {4, 4}};
    int bonus = 0;
    for (int[] sq : centerSquares) {
      if (row == sq[0] && col == sq[1]) {
        bonus = 20;
        break;
      }
    }
    int distanceFromEdge = Math.min(Math.min(row, 7 - row), Math.min(col, 7 - col));
    if (distanceFromEdge <= 1) bonus += 10;
    return bonus;
  }
}
