package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

/**
 * Encodes a board as a sparse 769-dimensional feature vector.
 *
 * <p>Indices 0–767: one-hot piece-on-square (12 piece types × 64 squares). Index 768: side to move
 * (1.0 = White, 0.0 = Black).
 */
public class NNUEFeatureExtractor {

  public static final int PIECE_TYPES = 12;
  public static final int SQUARES = 64;
  public static final int INPUT_SIZE = 769;

  /** Builds the sparse input vector for the network. */
  public double[] extract(Board board) {
    double[] features = new double[INPUT_SIZE];

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board.getPiece(new Position(row, column));
        if (piece == null) continue;
        int index = getPieceType(piece) * 64 + (row * 8 + column);
        features[index] = 1.0;
      }
    }

    features[768] = board.isWhiteToMove() ? 1.0 : 0.0;
    return features;
  }

  /** Piece type index: 0–5 White (P,N,B,R,Q,K), 6–11 Black. */
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

  /** Feature index for incremental updates (future use). */
  public int getFeatureIndex(Piece piece, Position position) {
    return getPieceType(piece) * 64 + position.getRow() * 8 + position.getColumn();
  }
}
