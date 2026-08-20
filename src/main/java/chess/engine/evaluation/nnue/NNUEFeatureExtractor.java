package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

public class NNUEFeatureExtractor {

  public static final int PIECE_TYPES = 12;
  public static final int SQUARES = 64;

  /*
   * 769 original features:
   * 768 piece/square features
   * 1 side to move
   *
   * Additional positional features:
   * 12 development features
   * 16 material/count features
   * 8 center/control features
   * 8 pawn structure/advancement features
   * 8 king safety/activity features
   */
  public static final int BASE_INPUT_SIZE = 769;
  public static final int EXTRA_FEATURES = 52;
  public static final int INPUT_SIZE = BASE_INPUT_SIZE + EXTRA_FEATURES;

  public double[] extract(Board board) {
    double[] features = new double[INPUT_SIZE];

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board.getPiece(new Position(row, column));
        if (piece == null) continue;

        int index = getPieceType(piece) * 64 + row * 8 + column;
        features[index] = 1.0;
      }
    }

    features[768] = board.isWhiteToMove() ? 1.0 : 0.0;

    addDevelopmentFeatures(board, features);
    addMaterialFeatures(board, features);
    addCenterFeatures(board, features);
    addPawnFeatures(board, features);
    addKingFeatures(board, features);

    return features;
  }

  private void addDevelopmentFeatures(Board board, double[] f) {
    int i = BASE_INPUT_SIZE;

    Position wn1 = new Position(7, 1);
    Position wn2 = new Position(7, 6);
    Position bn1 = new Position(0, 1);
    Position bn2 = new Position(0, 6);

    Position wb1 = new Position(7, 2);
    Position wb2 = new Position(7, 5);
    Position bb1 = new Position(0, 2);
    Position bb2 = new Position(0, 5);

    Position wq = new Position(7, 3);
    Position bq = new Position(0, 3);

    f[i++] = hasMovedFromSquare(board, wn1, Knight.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, wn2, Knight.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, bn1, Knight.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, bn2, Knight.class) ? 1 : 0;

    f[i++] = hasMovedFromSquare(board, wb1, Bishop.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, wb2, Bishop.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, bb1, Bishop.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, bb2, Bishop.class) ? 1 : 0;

    f[i++] = hasMovedFromSquare(board, wq, Queen.class) ? 1 : 0;
    f[i++] = hasMovedFromSquare(board, bq, Queen.class) ? 1 : 0;

    f[i++] = developedWhitePieces(board) / 4.0;
    f[i++] = developedBlackPieces(board) / 4.0;
  }

  private boolean hasMovedFromSquare(Board board, Position square, Class<?> type) {
    Piece piece = board.getPiece(square);
    return piece == null || !type.isInstance(piece);
  }

  private int developedWhitePieces(Board board) {
    int count = 0;
    if (hasMovedFromSquare(board, new Position(7, 1), Knight.class)) count++;
    if (hasMovedFromSquare(board, new Position(7, 6), Knight.class)) count++;
    if (hasMovedFromSquare(board, new Position(7, 2), Bishop.class)) count++;
    if (hasMovedFromSquare(board, new Position(7, 5), Bishop.class)) count++;
    return count;
  }

  private int developedBlackPieces(Board board) {
    int count = 0;
    if (hasMovedFromSquare(board, new Position(0, 1), Knight.class)) count++;
    if (hasMovedFromSquare(board, new Position(0, 6), Knight.class)) count++;
    if (hasMovedFromSquare(board, new Position(0, 2), Bishop.class)) count++;
    if (hasMovedFromSquare(board, new Position(0, 5), Bishop.class)) count++;
    return count;
  }

  private void addMaterialFeatures(Board board, double[] f) {
    int i = BASE_INPUT_SIZE + 12;

    int wp = 0, wn = 0, wb = 0, wr = 0, wq = 0;
    int bp = 0, bn = 0, bb = 0, br = 0, bq = 0;

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Piece p = board.getPiece(new Position(row, col));
        if (p == null) continue;

        if (p.isWhite()) {
          if (p instanceof Pawn) wp++;
          else if (p instanceof Knight) wn++;
          else if (p instanceof Bishop) wb++;
          else if (p instanceof Rook) wr++;
          else if (p instanceof Queen) wq++;
        } else {
          if (p instanceof Pawn) bp++;
          else if (p instanceof Knight) bn++;
          else if (p instanceof Bishop) bb++;
          else if (p instanceof Rook) br++;
          else if (p instanceof Queen) bq++;
        }
      }
    }

    f[i++] = wp / 8.0;
    f[i++] = wn / 2.0;
    f[i++] = wb / 2.0;
    f[i++] = wr / 2.0;
    f[i++] = wq;

    f[i++] = bp / 8.0;
    f[i++] = bn / 2.0;
    f[i++] = bb / 2.0;
    f[i++] = br / 2.0;
    f[i++] = bq;

    f[i++] = (wp - bp) / 8.0;
    f[i++] = (wn - bn) / 2.0;

    f[i++] = (wb - bb) / 2.0;
    f[i++] = (wr - br) / 2.0;
    f[i++] = wq - bq;
    f[i++] = (wp + wn + wb + wr + wq - bp - bn - bb - br - bq) / 16.0;
  }

  private void addCenterFeatures(Board board, double[] f) {
    int i = BASE_INPUT_SIZE + 28;

    int whiteCenter = 0;
    int blackCenter = 0;

    int[][] centers = {{3, 3}, {3, 4}, {4, 3}, {4, 4}};

    for (int[] square : centers) {
      Piece p = board.getPiece(new Position(square[0], square[1]));
      if (p == null) continue;
      if (p.isWhite()) whiteCenter++;
      else blackCenter++;
    }

    f[i++] = whiteCenter / 4.0;
    f[i++] = blackCenter / 4.0;
    f[i++] = (whiteCenter - blackCenter) / 4.0;

    f[i++] = pawnOnCenter(board, true) / 2.0;
    f[i++] = pawnOnCenter(board, false) / 2.0;

    f[i++] = centerPieceCount(board, true) / 8.0;
    f[i++] = centerPieceCount(board, false) / 8.0;
    f[i] = (centerPieceCount(board, true) - centerPieceCount(board, false)) / 8.0;
  }

  private int pawnOnCenter(Board board, boolean white) {
    int count = 0;
    int[][] centers = {{3, 3}, {3, 4}, {4, 3}, {4, 4}};

    for (int[] square : centers) {
      Piece p = board.getPiece(new Position(square[0], square[1]));
      if (p instanceof Pawn && p.isWhite() == white) count++;
    }

    return count;
  }

  private int centerPieceCount(Board board, boolean white) {
    int count = 0;

    for (int row = 2; row <= 5; row++) {
      for (int col = 2; col <= 5; col++) {
        Piece p = board.getPiece(new Position(row, col));
        if (p != null && p.isWhite() == white) count++;
      }
    }

    return count;
  }

  private void addPawnFeatures(Board board, double[] f) {
    int i = BASE_INPUT_SIZE + 36;

    int whitePawns = 0;
    int blackPawns = 0;
    int whiteAdvanced = 0;
    int blackAdvanced = 0;

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        Piece p = board.getPiece(new Position(row, col));
        if (!(p instanceof Pawn)) continue;

        if (p.isWhite()) {
          whitePawns++;
          if (row <= 3) whiteAdvanced++;
        } else {
          blackPawns++;
          if (row >= 4) blackAdvanced++;
        }
      }
    }

    f[i++] = whitePawns / 8.0;
    f[i++] = blackPawns / 8.0;
    f[i++] = whiteAdvanced / 8.0;
    f[i++] = blackAdvanced / 8.0;

    f[i++] = whitePawns > 0 ? 1.0 : 0.0;
    f[i++] = blackPawns > 0 ? 1.0 : 0.0;
    f[i++] = whiteAdvanced / 8.0 - blackAdvanced / 8.0;
    f[i] = (whitePawns - blackPawns) / 8.0;
  }

  private void addKingFeatures(Board board, double[] f) {
    int i = BASE_INPUT_SIZE + 44;

    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);

    if (whiteKing != null) {
      f[i++] = whiteKing.getRow() / 7.0;
      f[i++] = whiteKing.getColumn() / 7.0;
    } else {
      i += 2;
    }

    if (blackKing != null) {
      f[i++] = (7 - blackKing.getRow()) / 7.0;
      f[i++] = blackKing.getColumn() / 7.0;
    } else {
      i += 2;
    }

    f[i++] = whiteKing != null && whiteKing.getRow() == 7 ? 1.0 : 0.0;
    f[i++] = blackKing != null && blackKing.getRow() == 0 ? 1.0 : 0.0;

    f[i++] = whiteKing != null && (whiteKing.getColumn() == 6 || whiteKing.getColumn() == 2) ? 1.0 : 0.0;
    f[i] = blackKing != null && (blackKing.getColumn() == 6 || blackKing.getColumn() == 2) ? 1.0 : 0.0;
  }

  private int getPieceType(Piece piece) {
    int offset = piece.isWhite() ? 0 : 6;
    if (piece instanceof Pawn) return offset;
    if (piece instanceof Knight) return offset + 1;
    if (piece instanceof Bishop) return offset + 2;
    if (piece instanceof Rook) return offset + 3;
    if (piece instanceof Queen) return offset + 4;
    if (piece instanceof King) return offset + 5;
    throw new IllegalArgumentException("Unknown piece type: " + piece.getClass().getName());
  }

  public int getFeatureIndex(Piece piece, Position position) {
    return getPieceType(piece) * 64 + position.getRow() * 8 + position.getColumn();
  }
}