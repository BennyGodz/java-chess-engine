package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

import java.util.BitSet;

/**
 * Improved NNUE feature extractor with:
 * - Piece-square features with color sign (769 total inputs)
 * - Full king position encoding
 * - Pawn structure features (passed, backward, connected)
 * - King safety features (shielding, distance)
 * - En passant and castling features
 */
public class NNUEFeatureExtractor {

  /** Total input size: 769 features */
  public static final int INPUT_SIZE = 769;

  /**
   * Extracts features from a board position for NNUE evaluation.
   * Returns array of size INPUT_SIZE with double values.
   * Positive values indicate white features, negative indicate black features.
   */
  public double[] extract(Board board) {
    double[] features = new double[INPUT_SIZE];

    // ============
    // Piece-Square Features (indices 0-383)
    // 6 piece types × 64 squares = 384 features
    // White pieces: positive values (+1.0)
    // Black pieces: negative values (-1.0)
    // This allows the network to distinguish color via sign
    // ============
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

    // ============
    // White King Position Features (indices 384-511)
    // 128 features - one-hot encoding of white king square
    // squareIndex 0-63 maps to features 384-447
    // ============
    Position whiteKing = board.findKing(true);
    if (whiteKing != null) {
      int squareIndex = whiteKing.getRow() * 8 + whiteKing.getColumn();
      if (squareIndex >= 0 && squareIndex <= 63) {
        features[384 + squareIndex] = 1.0;
      }
      // King safety: center control bonus
      int centerBonus = getKingCenterBonus(whiteKing);
      if (centerBonus > 0) {
        features[511] = centerBonus / 100.0;
      }
    }

    // ============
    // Black King Position Features (indices 512-639)
    // 128 features - one-hot encoding of black king square
    // ============
    Position blackKing = board.findKing(false);
    if (blackKing != null) {
      int squareIndex = blackKing.getRow() * 8 + blackKing.getColumn();
      if (squareIndex >= 0 && squareIndex <= 63) {
        features[512 + squareIndex] = 1.0;
      }
      // King safety: center control bonus (negative for black, evaluated from white perspective)
      int centerBonus = getKingCenterBonus(blackKing);
      if (centerBonus > 0) {
        features[639] = -centerBonus / 100.0;
      }
    }

    // ============
    // Pawn Structure Features (indices 640-663, 24 features)
    // ============
    addPawnStructureFeatures(board, features);

    // ============
    // King Safety Features (indices 664-679, 16 features)
    // ============
    addKingSafetyFeatures(board, features);

    // ============
    // Castling Rights Features (indices 680-683, 4 features)
    // ============
    features[680] = board.canCastleKingside(true) ? 1.0 : 0.0;
    features[681] = board.canCastleQueenside(true) ? 1.0 : 0.0;
    features[682] = board.canCastleKingside(false) ? -1.0 : 0.0;
    features[683] = board.canCastleQueenside(false) ? -1.0 : 0.0;

    // ============
    // En Passant Target Feature (index 684)
    // ============
    features[684] = board.getEnPassantTarget() != null ? 1.0 : 0.0;
    if (board.getEnPassantTarget() != null && !board.isWhiteToMove()) {
      features[684] = -1.0;
    }

    // ============
    // Side to Move Feature (index 768)
    // ============
    features[768] = board.isWhiteToMove() ? 1.0 : 0.0;

    return features;
  }

  /**
   * Adds pawn structure features to the feature vector.
   */
  private void addPawnStructureFeatures(Board board, double[] features) {
    boolean[] whitePawnFiles = new boolean[8];
    boolean[] blackPawnFiles = new boolean[8];
    int[] whitePawnCountPerFile = new int[8];
    int[] blackPawnCountPerFile = new int[8];

    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);
    int whiteKingRow = whiteKing != null ? whiteKing.getRow() : -1;
    int whiteKingFile = whiteKing != null ? whiteKing.getColumn() : -1;
    int blackKingRow = blackKing != null ? blackKing.getRow() : -1;
    int blackKingFile = blackKing != null ? blackKing.getColumn() : -1;

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

    // ============
    // Passed Pawn Features (indices 640-641)
    // ============
    int whitePassedBonus = calculatePassedPawnBonus(board, true);
    int blackPassedBonus = calculatePassedPawnBonus(board, false);
    features[640] = Math.min(whitePassedBonus / 10.0, 50.0);
    features[641] = Math.min(-blackPassedBonus / 10.0, 50.0);

    // ============
    // Isolated Pawn Features (indices 642-643)
    // ============
    boolean whiteHasIsolated = false;
    boolean blackHasIsolated = false;

    for (int f = 0; f < 8; f++) {
      boolean whiteAdjacent = (f > 0 && whitePawnFiles[f - 1]) || (f < 7 && whitePawnFiles[f + 1]);
      boolean blackAdjacent = (f > 0 && blackPawnFiles[f - 1]) || (f < 7 && blackPawnFiles[f + 1]);
      if (whitePawnFiles[f] && !whiteAdjacent) whiteHasIsolated = true;
      if (blackPawnFiles[f] && !blackAdjacent) blackHasIsolated = true;
    }

    features[642] = whiteHasIsolated ? -30.0 : 0.0;
    features[643] = blackHasIsolated ? -30.0 : 0.0;

    // ============
    // Connected Pawn Chain Features (indices 644-645)
    // ============
    boolean whiteHasConnected = false;
    boolean blackHasConnected = false;

    for (int f = 0; f < 8; f++) {
      if (whitePawnFiles[f]) {
        boolean left = f > 0 && whitePawnFiles[f - 1];
        boolean right = f < 7 && whitePawnFiles[f + 1];
        if (left || right) { whiteHasConnected = true; break; }
      }
      if (blackPawnFiles[f]) {
        boolean left = f > 0 && blackPawnFiles[f - 1];
        boolean right = f < 7 && blackPawnFiles[f + 1];
        if (left || right) { blackHasConnected = true; break; }
      }
    }

    features[644] = whiteHasConnected ? 20.0 : 0.0;
    features[645] = blackHasConnected ? -20.0 : 0.0;

    // ============
    // Backward Pawn Features (indices 646-647)
    // ============
    boolean whiteHasBackward = false;
    boolean blackHasBackward = false;

    for (int f = 0; f < 8; f++) {
      if (whitePawnFiles[f]) {
        boolean hasAdjacent = (f > 0 && whitePawnFiles[f - 1]) || (f < 7 && whitePawnFiles[f + 1]);
        if (!hasAdjacent) { whiteHasBackward = true; break; }
      }
      if (blackPawnFiles[f]) {
        boolean hasAdjacent = (f > 0 && blackPawnFiles[f - 1]) || (f < 7 && blackPawnFiles[f + 1]);
        if (!hasAdjacent) { blackHasBackward = true; break; }
      }
    }

    features[646] = whiteHasBackward ? -20.0 : 0.0;
    features[647] = blackHasBackward ? -20.0 : 0.0;

    // ============
    // Pawn Count Per File (indices 648-663)
    // ============
    for (int f = 0; f < 8; f++) {
      features[648 + f] = whitePawnCountPerFile[f] / 8.0;
      features[656 + f] = -blackPawnCountPerFile[f] / 8.0;
    }
  }

  /**
   * Calculates passed pawn bonus from the given perspective.
   * Positive value from white's perspective, negative from black's.
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
        for (int r = row + direction; r >= 0 && r < 8; r += direction) {
          if (isValidPos(r, col)) {
            Piece checking = board.getPiece(new Position(r, col));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) { isPassed = false; break; }
          }
          if (isValidPos(r, col - 1) && col - 1 >= 0) {
            Piece checking = board.getPiece(new Position(r, col - 1));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) { isPassed = false; break; }
          }
          if (isValidPos(r, col + 1) && col + 1 < 8) {
            Piece checking = board.getPiece(new Position(r, col + 1));
            if (checking != null && checking.isWhite() != white && checking instanceof Pawn) { isPassed = false; break; }
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

  private boolean isValidPos(int row, int col) {
    return row >= 0 && row < 8 && col >= 0 && col < 8;
  }

  // Pawn count per file already added above

  /**
   * Adds king safety features to the feature vector.
   */
  private void addKingSafetyFeatures(Board board, double[] features) {
    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);

    if (whiteKing == null || blackKing == null) {
      for (int i = 664; i <= 679; i++) features[i] = 0.0;
      return;
    }

    int whiteKingRow = whiteKing.getRow();
    int whiteKingCol = whiteKing.getColumn();
    int blackKingRow = blackKing.getRow();
    int blackKingCol = blackKing.getColumn();

    // Index 670: King distance (normalized)
    int manhattanDist = Math.abs(whiteKingRow - blackKingRow) + Math.abs(whiteKingCol - blackKingCol);
    features[670] = manhattanDist / 14.0;

    // Index 671: Same color complex (1.0) or opposite color (-1.0)
    int whiteKingColor = (whiteKingRow + whiteKingCol) & 1;
    int blackKingColor = (blackKingRow + blackKingCol) & 1;
    features[671] = whiteKingColor == blackKingColor ? 1.0 : -1.0;

    // Index 672: White king attackers near king (normalized)
    features[672] = countAttackersNearKing(board, true, whiteKing) / 8.0;

    // Index 673: Black king attackers (negated from white perspective)
    features[673] = -countAttackersNearKing(board, false, blackKing) / 8.0;

    // Index 674: White king shielded (1.0 if has pawn shield)
    features[674] = isKingShielded(board, true, whiteKing) ? 1.0 : 0.0;

    // Index 675: Black king shielded (negated)
    features[675] = isKingShielded(board, false, blackKing) ? -1.0 : 0.0;

    // Index 676: White king mobility (normalized)
    features[676] = calculateKingMobility(board, true, whiteKing) / 8.0;

    // Index 677: Black king mobility (negated)
    features[677] = -calculateKingMobility(board, false, blackKing) / 8.0;

    // Index 678: White king center control (0.0 to 1.0)
    features[678] = calculateKingCenterControl(board, true) / 4.0;

    // Index 679: Black king center control (negated)
    features[679] = -calculateKingCenterControl(board, false) / 4.0;
  }

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

  /** Returns a center bonus for king position (20 for central, less near edges). */
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