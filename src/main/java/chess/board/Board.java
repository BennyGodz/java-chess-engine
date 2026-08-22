package chess.board;

import chess.engine.MoveGenerator;
import chess.pieces.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The chess board: piece placement, move execution and every game-end rule.
 *
 * <p>Coordinates use row 0..7 for ranks 8..1 and column 0..7 for files a..h. The board also tracks
 * castling rights, the en passant target, the halfmove clock, the fullmove number, repetition
 * counts and a Zobrist hash of the position.
 */
public class Board {

  private final Piece[][] board;

  private boolean whiteToMove = true;

  private boolean whiteKingsideCastle = true;
  private boolean whiteQueensideCastle = true;
  private boolean blackKingsideCastle = true;
  private boolean blackQueensideCastle = true;

  private Position enPassantTarget = null;

  /** Half-moves since the last pawn move or capture; used for the 50/75-move rules. */
  private int halfmoveClock = 0;

  /** Fullmove number; starts at 1 and increments after Black moves. */
  private int fullmoveNumber = 1;

  /** How often each position has occurred; used for repetition draws. */
  private final Map<String, Integer> repetitionCounts = new HashMap<>();

  /** Zobrist hash of the current position. */
  private long zobristKey;

  // Random but fixed Zobrist keys, one per piece type/square, castling right set and en passant square.
  private static final long[][] PIECE_KEYS;
  private static final long[] CASTLING_KEYS;
  private static final long[] EN_PASSANT_KEYS;
  private static final long SIDE_TO_MOVE_KEY;

  static {
    java.util.Random rand = new java.util.Random(0);
    PIECE_KEYS = new long[6][64];
    CASTLING_KEYS = new long[16];
    EN_PASSANT_KEYS = new long[64];
    for (int p = 0; p < 6; p++) {
      for (int s = 0; s < 64; s++) {
        PIECE_KEYS[p][s] = rand.nextLong();
      }
    }
    for (int i = 0; i < 16; i++) {
      CASTLING_KEYS[i] = rand.nextLong();
    }
    for (int i = 0; i < 64; i++) {
      EN_PASSANT_KEYS[i] = rand.nextLong();
    }
    SIDE_TO_MOVE_KEY = rand.nextLong();
  }

  /** Creates a board in the standard starting position. */
  public Board() {
    board = new Piece[8][8];
    setupStartingPosition();
    repetitionCounts.put(getPositionKey(), 1);
    this.zobristKey = initZobristKey();
  }

  /**
   * Computes the Zobrist hash for the current position by XOR-ing together the keys of every
   * piece, the castling rights, the en passant square and the side to move.
   */
  private long initZobristKey() {
    long key = 0;
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece == null) {
          continue;
        }
        int pieceType = pieceTypeIndex(piece);
        key ^= Board.PIECE_KEYS[pieceType][row * 8 + column];
      }
    }
    int castling = 0;
    if (whiteKingsideCastle) castling |= 8;
    if (whiteQueensideCastle) castling |= 4;
    if (blackKingsideCastle) castling |= 2;
    if (blackQueensideCastle) castling |= 1;
    key ^= Board.CASTLING_KEYS[castling];
    if (enPassantTarget != null) {
      key ^= Board.EN_PASSANT_KEYS[enPassantTarget.getRow() * 8 + enPassantTarget.getColumn()];
    }
    key ^= (whiteToMove ? 0 : Board.SIDE_TO_MOVE_KEY);
    return key;
  }

  /** Maps a piece to its Zobrist table index: Pawn=0, Knight=1, Bishop=2, Rook=3, Queen=4, King=5. */
  private static int pieceTypeIndex(Piece piece) {
    if (piece instanceof Pawn) return 0;
    if (piece instanceof Knight) return 1;
    if (piece instanceof Bishop) return 2;
    if (piece instanceof Rook) return 3;
    if (piece instanceof Queen) return 4;
    return 5;
  }

  /**
   * Deep copy of another board, used to simulate moves without touching the original. History is
   * copied so state queries on the copy stay consistent.
   */
  public Board(Board other) {
    board = new Piece[8][8];

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        board[row][column] = copyPiece(other.board[row][column]);
      }
    }

    whiteToMove = other.whiteToMove;
    whiteKingsideCastle = other.whiteKingsideCastle;
    whiteQueensideCastle = other.whiteQueensideCastle;
    blackKingsideCastle = other.blackKingsideCastle;
    blackQueensideCastle = other.blackQueensideCastle;

    if (other.enPassantTarget != null) {
      enPassantTarget =
          new Position(other.enPassantTarget.getRow(), other.enPassantTarget.getColumn());
    }

    halfmoveClock = other.halfmoveClock;
    fullmoveNumber = other.fullmoveNumber;
    repetitionCounts.putAll(other.repetitionCounts);
  }

  /** Creates a fresh instance of the given piece's exact type. */
  private Piece copyPiece(Piece piece) {
    if (piece instanceof Pawn) return new Pawn(piece.isWhite());
    if (piece instanceof Knight) return new Knight(piece.isWhite());
    if (piece instanceof Bishop) return new Bishop(piece.isWhite());
    if (piece instanceof Rook) return new Rook(piece.isWhite());
    if (piece instanceof Queen) return new Queen(piece.isWhite());
    if (piece instanceof King) return new King(piece.isWhite());
    return null;
  }

  /** Places all 32 pieces on their starting squares. */
  private void setupStartingPosition() {
    board[0][0] = new Rook(false);
    board[0][1] = new Knight(false);
    board[0][2] = new Bishop(false);
    board[0][3] = new Queen(false);
    board[0][4] = new King(false);
    board[0][5] = new Bishop(false);
    board[0][6] = new Knight(false);
    board[0][7] = new Rook(false);

    for (int column = 0; column < 8; column++) {
      board[1][column] = new Pawn(false);
      board[6][column] = new Pawn(true);
    }

    board[7][0] = new Rook(true);
    board[7][1] = new Knight(true);
    board[7][2] = new Bishop(true);
    board[7][3] = new Queen(true);
    board[7][4] = new King(true);
    board[7][5] = new Bishop(true);
    board[7][6] = new Knight(true);
    board[7][7] = new Rook(true);
  }

  /** Returns whether it is White's turn. */
  public boolean isWhiteToMove() {
    return whiteToMove;
  }

  /** Returns the number of half-moves since the last pawn move or capture. */
  public int getHalfmoveClock() {
    return halfmoveClock;
  }

  /** Returns the current fullmove number. */
  public int getFullmoveNumber() {
    return fullmoveNumber;
  }

  /** Returns the piece on a square, or null when the square is empty. */
  public Piece getPiece(Position position) {
    return board[position.getRow()][position.getColumn()];
  }

  /** Places a piece on a square, replacing whatever was there. */
  public void setPiece(Position position, Piece piece) {
    board[position.getRow()][position.getColumn()] = piece;
  }

  /** Returns whether the position is on the board and empty. */
  public boolean isEmpty(Position position) {
    return getPiece(position) == null;
  }

  /** Returns whether an enemy piece (from {@code white}'s point of view) stands on the square. */
  public boolean isEnemyPiece(Position position, boolean white) {
    Piece piece = getPiece(position);
    return piece != null && piece.isWhite() != white;
  }

  /** Returns whether a row/column pair lies on the board. */
  public boolean isValid(int row, int column) {
    return row >= 0 && row < 8 && column >= 0 && column < 8;
  }

  /** Returns whether the square is on the board and empty. */
  public boolean isEmpty(int row, int column) {
    return isValid(row, column) && board[row][column] == null;
  }

  /** Returns whether the square holds an enemy piece from {@code white}'s point of view. */
  public boolean hasEnemyPiece(int row, int column, boolean white) {
    return isValid(row, column)
        && board[row][column] != null
        && board[row][column].isWhite() != white;
  }

  /** Returns whether the square is the current en passant target. */
  public boolean isEnPassantTarget(Position position) {
    return enPassantTarget != null && enPassantTarget.equals(position);
  }

  /** Returns the current en passant target square, or null when there is none. */
  public Position getEnPassantTarget() {
    return enPassantTarget;
  }

  /**
   * Returns whether the side may castle kingside: rights intact, king and rook on their home
   * squares, squares between them empty and none of the king's path attacked.
   */
  public boolean canCastleKingside(boolean white) {
    int row = white ? 7 : 0;
    boolean rights = white ? whiteKingsideCastle : blackKingsideCastle;
    if (!rights) return false;

    Piece king = board[row][4];
    Piece rook = board[row][7];
    if (!(king instanceof King) || king.isWhite() != white) return false;
    if (!(rook instanceof Rook) || rook.isWhite() != white) return false;

    if (!isEmpty(new Position(row, 5)) || !isEmpty(new Position(row, 6))) return false;

    return !isSquareAttacked(new Position(row, 4), !white)
        && !isSquareAttacked(new Position(row, 5), !white)
        && !isSquareAttacked(new Position(row, 6), !white);
  }

  /**
   * Returns whether the side may castle queenside: rights intact, king and rook on their home
   * squares, squares between them empty and none of the king's path attacked.
   */
  public boolean canCastleQueenside(boolean white) {
    int row = white ? 7 : 0;
    boolean rights = white ? whiteQueensideCastle : blackQueensideCastle;
    if (!rights) return false;

    Piece king = board[row][4];
    Piece rook = board[row][0];
    if (!(king instanceof King) || king.isWhite() != white) return false;
    if (!(rook instanceof Rook) || rook.isWhite() != white) return false;

    if (!isEmpty(new Position(row, 1))
        || !isEmpty(new Position(row, 2))
        || !isEmpty(new Position(row, 3))) return false;

    return !isSquareAttacked(new Position(row, 4), !white)
        && !isSquareAttacked(new Position(row, 3), !white)
        && !isSquareAttacked(new Position(row, 2), !white);
  }

  /** Returns the square of the given side's king, or null if it is missing. */
  public Position findKing(boolean white) {
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece instanceof King && piece.isWhite() == white) {
          return new Position(row, column);
        }
      }
    }
    return null;
  }

  /**
   * Returns whether a square is attacked by any piece of the given colour. Checks pawns, knights,
   * kings and sliding pieces directly instead of generating legal moves, which makes it fast
   * enough for check detection during search.
   */
  public boolean isSquareAttacked(Position square, boolean byWhite) {
    int targetRow = square.getRow();
    int targetColumn = square.getColumn();

    // Pawns attack diagonally "backwards" relative to their movement direction.
    int pawnRow = targetRow + (byWhite ? 1 : -1);
    if (isValid(pawnRow, targetColumn - 1)) {
      Piece piece = board[pawnRow][targetColumn - 1];
      if (piece instanceof Pawn && piece.isWhite() == byWhite) return true;
    }
    if (isValid(pawnRow, targetColumn + 1)) {
      Piece piece = board[pawnRow][targetColumn + 1];
      if (piece instanceof Pawn && piece.isWhite() == byWhite) return true;
    }

    // Knights.
    int[][] knightOffsets = {
      {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
      {1, -2}, {1, 2}, {2, -1}, {2, 1}
    };
    for (int[] offset : knightOffsets) {
      int row = targetRow + offset[0];
      int column = targetColumn + offset[1];
      if (!isValid(row, column)) continue;
      Piece piece = board[row][column];
      if (piece instanceof Knight && piece.isWhite() == byWhite) return true;
    }

    // Adjacent kings.
    for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
      for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
        if (rowOffset == 0 && columnOffset == 0) continue;
        int row = targetRow + rowOffset;
        int column = targetColumn + columnOffset;
        if (!isValid(row, column)) continue;
        Piece piece = board[row][column];
        if (piece instanceof King && piece.isWhite() == byWhite) return true;
      }
    }

    // Rooks and queens along ranks/files, then bishops and queens along diagonals.
    if (lineAttacked(targetRow, targetColumn, byWhite, -1, 0, Rook.class)
        || lineAttacked(targetRow, targetColumn, byWhite, 1, 0, Rook.class)
        || lineAttacked(targetRow, targetColumn, byWhite, 0, -1, Rook.class)
        || lineAttacked(targetRow, targetColumn, byWhite, 0, 1, Rook.class)) {
      return true;
    }

    return lineAttacked(targetRow, targetColumn, byWhite, -1, -1, Bishop.class)
        || lineAttacked(targetRow, targetColumn, byWhite, -1, 1, Bishop.class)
        || lineAttacked(targetRow, targetColumn, byWhite, 1, -1, Bishop.class)
        || lineAttacked(targetRow, targetColumn, byWhite, 1, 1, Bishop.class);
  }

  /**
   * Walks outward from a square in one direction until a piece is found; returns whether that
   * first piece is an enemy slider able to attack along this direction (a rook-type or queen).
   */
  private boolean lineAttacked(
      int targetRow,
      int targetColumn,
      boolean byWhite,
      int rowStep,
      int columnStep,
      Class<?> slidingType) {
    int row = targetRow + rowStep;
    int column = targetColumn + columnStep;

    while (isValid(row, column)) {
      Piece piece = board[row][column];
      if (piece != null) {
        return piece.isWhite() == byWhite
            && (slidingType.isInstance(piece) || piece instanceof Queen);
      }
      row += rowStep;
      column += columnStep;
    }
    return false;
  }

  /** Returns whether the given side's king is in check. */
  public boolean isInCheck(boolean white) {
    Position king = findKing(white);
    return king != null && isSquareAttacked(king, !white);
  }

  /** Generates every legal move for the given side across the whole board. */
  public List<Move> getLegalMoves(boolean white) {
    List<Move> legalMoves = new ArrayList<>();
    MoveGenerator generator = new MoveGenerator();

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece == null || piece.isWhite() != white) continue;

        Position position = new Position(row, column);
        legalMoves.addAll(generator.generateLegalMoves(piece, position, this));
      }
    }

    return legalMoves;
  }

  /** Generates the legal moves of the piece standing on one specific square. */
  public List<Move> getLegalMoves(Position position) {
    Piece piece = getPiece(position);
    if (piece == null) return Collections.emptyList();
    return new MoveGenerator().generateLegalMoves(piece, position, this);
  }

  /**
   * Legacy coordinate API: plays a non-promotion move chosen only by its start and end squares.
   * For promotions callers must use {@link #playMove(Move)} with a specific Move.
   *
   * @return whether a matching legal move existed and was played
   */
  public boolean movePiece(Position start, Position end) {
    Piece piece = getPiece(start);
    if (piece == null || piece.isWhite() != whiteToMove) return false;

    List<Move> candidates = getLegalMoves(start);
    Move selected = null;
    for (Move move : candidates) {
      if (move.getEnd().equals(end)) {
        if (!move.isPromotion()) {
          selected = move;
          break;
        }
      }
    }
    if (selected == null) return false;
    playMove(selected);
    return true;
  }

  /**
   * Finds the legal move from start to end, optionally restricted to one promotion choice ('Q',
   * 'R', 'B' or 'N'). Returns null when no such legal move exists.
   */
  public Move findLegalMove(Position start, Position end, char promotionChoice) {
    for (Move move : getLegalMoves(start)) {
      if (!move.getEnd().equals(end)) continue;
      if (!move.isPromotion()) return move;

      char choice = Character.toUpperCase(promotionChoice);
      if (choice == 'Q' && move.getPromotionPiece() instanceof Queen) return move;
      if (choice == 'R' && move.getPromotionPiece() instanceof Rook) return move;
      if (choice == 'B' && move.getPromotionPiece() instanceof Bishop) return move;
      if (choice == 'N' && move.getPromotionPiece() instanceof Knight) return move;
    }
    return null;
  }

  /**
   * Plays a legal move and updates all bookkeeping: halfmove clock, fullmove number, side to move,
   * repetition counts and the Zobrist key.
   */
  public void playMove(Move move) {
    if (move == null) throw new IllegalArgumentException("Move cannot be null.");

    Piece movingPiece = getPiece(move.getStart());
    if (movingPiece == null || movingPiece.isWhite() != whiteToMove) {
      throw new IllegalArgumentException("That piece cannot move now.");
    }

    boolean capture = isCapture(move);
    makeMove(move);

    // The 50-move clock resets after any pawn move or capture.
    if (movingPiece instanceof Pawn || capture) halfmoveClock = 0;
    else halfmoveClock++;

    if (!whiteToMove) fullmoveNumber++;
    whiteToMove = !whiteToMove;

    repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
    this.zobristKey = initZobristKey();
  }

  /** Returns the Zobrist hash of the current position. */
  public long getZobristKey() {
    return zobristKey;
  }

  /** Returns whether the move captures something (normally or en passant). */
  private boolean isCapture(Move move) {
    return move.isEnPassant() || getPiece(move.getEnd()) != null;
  }

  /**
   * Applies a move to the piece placement only: moves the piece, handles captures, en passant,
   * castling rook moves and promotions, then updates castling rights and the en passant target.
   * Counters and repetition state are handled by {@link #playMove}; this method alone is used for
   * simulation inside search.
   */
  public void makeMove(Move move) {
    Position start = move.getStart();
    Position end = move.getEnd();
    Piece movingPiece = getPiece(start);
    if (movingPiece == null) throw new IllegalArgumentException("No piece on " + start);

    Piece capturedPiece = getPiece(end);
    if (capturedPiece instanceof Rook) {
      removeCastlingRightForRookCapture(end, capturedPiece.isWhite());
    }

    setPiece(start, null);

    if (move.isEnPassant()) {
      int capturedPawnRow = end.getRow() + (movingPiece.isWhite() ? 1 : -1);
      setPiece(new Position(capturedPawnRow, end.getColumn()), null);
    }

    if (move.isCastling()) {
      int row = start.getRow();
      setPiece(end, movingPiece);

      // Move the rook to its castled square as well.
      if (end.getColumn() == 6) {
        Piece rook = getPiece(new Position(row, 7));
        setPiece(new Position(row, 7), null);
        setPiece(new Position(row, 5), rook);
      } else {
        Piece rook = getPiece(new Position(row, 0));
        setPiece(new Position(row, 0), null);
        setPiece(new Position(row, 3), rook);
      }
    } else {
      setPiece(end, move.isPromotion() ? move.getPromotionPiece() : movingPiece);
    }

    updateCastlingRightsAfterMove(start, movingPiece);

    // A double pawn push sets the en passant target; anything else clears it.
    if (movingPiece instanceof Pawn && Math.abs(end.getRow() - start.getRow()) == 2) {
      enPassantTarget = new Position((start.getRow() + end.getRow()) / 2, start.getColumn());
    } else {
      enPassantTarget = null;
    }
  }

  /** Revokes castling rights when the king moves or the matching rook moves away. */
  private void updateCastlingRightsAfterMove(Position start, Piece movingPiece) {
    if (movingPiece instanceof King) {
      if (movingPiece.isWhite()) {
        whiteKingsideCastle = false;
        whiteQueensideCastle = false;
      } else {
        blackKingsideCastle = false;
        blackQueensideCastle = false;
      }
      return;
    }

    if (movingPiece instanceof Rook) {
      if (movingPiece.isWhite()) {
        if (start.equals(new Position(7, 0))) whiteQueensideCastle = false;
        if (start.equals(new Position(7, 7))) whiteKingsideCastle = false;
      } else {
        if (start.equals(new Position(0, 0))) blackQueensideCastle = false;
        if (start.equals(new Position(0, 7))) blackKingsideCastle = false;
      }
    }
  }

  /**
   * Creates a copy of this board with the move already played. Used by the search engine so the
   * searched Board itself is never mutated.
   */
  public Board copyAndPlayMoveForSearch(Move move) {
    Board copy = new Board(this);
    copy.playMove(move);
    return copy;
  }

  /** Removes the castling right of a rook that has been captured on its home square. */
  private void removeCastlingRightForRookCapture(Position square, boolean rookWhite) {
    if (rookWhite) {
      if (square.equals(new Position(7, 0))) whiteQueensideCastle = false;
      if (square.equals(new Position(7, 7))) whiteKingsideCastle = false;
    } else {
      if (square.equals(new Position(0, 0))) blackQueensideCastle = false;
      if (square.equals(new Position(0, 7))) blackKingsideCastle = false;
    }
  }

  /** Returns whether the given side is checkmated (in check with no legal moves). */
  public boolean isCheckmate(boolean white) {
    return isInCheck(white) && getLegalMoves(white).isEmpty();
  }

  /** Returns whether the given side is stalemated (not in check with no legal moves). */
  public boolean isStalemate(boolean white) {
    return !isInCheck(white) && getLegalMoves(white).isEmpty();
  }

  /** Returns whether the 50-move draw can be claimed by a player. */
  public boolean isFiftyMoveRule() {
    return halfmoveClock >= 100;
  }

  /** Returns whether the automatic seventy-five-move draw applies. */
  public boolean isSeventyFiveMoveRule() {
    return halfmoveClock >= 150;
  }

  /** Returns how many times the exact current position has occurred. */
  public int getCurrentPositionRepetitionCount() {
    return repetitionCounts.getOrDefault(getPositionKey(), 0);
  }

  /** Returns whether the same position has occurred at least three times (claimable draw). */
  public boolean isThreefoldRepetition() {
    return getCurrentPositionRepetitionCount() >= 3;
  }

  /** Returns whether the same position has occurred at least five times (automatic draw). */
  public boolean isFivefoldRepetition() {
    return getCurrentPositionRepetitionCount() >= 5;
  }

  /**
   * Detects dead positions where neither side can possibly mate: bare kings, king plus a single
   * minor piece versus a king, and opposite bishops on the same colour complex.
   */
  public boolean isInsufficientMaterial() {
    List<Piece> pieces = new ArrayList<>();
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece p = board[row][column];
        if (p != null) pieces.add(p);
      }
    }

    // Kings only.
    if (pieces.size() == 2) return true;

    // King plus one bishop or knight versus king.
    if (pieces.size() == 3) {
      for (Piece p : pieces) {
        if (p instanceof Bishop || p instanceof Knight) return true;
      }
      return false;
    }

    // King plus bishop versus king plus bishop, with both bishops on the same colour.
    if (pieces.size() == 4) {
      boolean allMinors = true;
      int whiteBishops = 0;
      int blackBishops = 0;
      Position whiteBishopSquare = null;
      Position blackBishopSquare = null;

      for (int row = 0; row < 8; row++) {
        for (int column = 0; column < 8; column++) {
          Piece p = board[row][column];
          if (p == null || p instanceof King) continue;
          if (!(p instanceof Bishop)) {
            allMinors = false;
          } else if (p.isWhite()) {
            whiteBishops++;
            whiteBishopSquare = new Position(row, column);
          } else {
            blackBishops++;
            blackBishopSquare = new Position(row, column);
          }
        }
      }

      if (allMinors
          && whiteBishops == 1
          && blackBishops == 1
          && whiteBishopSquare != null
          && blackBishopSquare != null) {
        int whiteColor = (whiteBishopSquare.getRow() + whiteBishopSquare.getColumn()) & 1;
        int blackColor = (blackBishopSquare.getRow() + blackBishopSquare.getColumn()) & 1;
        return whiteColor == blackColor;
      }
    }

    return false;
  }

  /**
   * Returns a human-readable description of the game state. Automatic game-ending conditions are
   * reported before claimable draws and ordinary turn information.
   */
  public String getGameStatus() {
    boolean side = whiteToMove;

    if (isCheckmate(side)) return (side ? "Black" : "White") + " wins by checkmate.";
    if (isStalemate(side)) return "Draw by stalemate.";
    if (isSeventyFiveMoveRule()) return "Draw by the 75-move rule.";
    if (isFivefoldRepetition()) return "Draw by fivefold repetition.";
    if (isInsufficientMaterial()) return "Draw by insufficient mating material.";
    if (isInCheck(side)) return (side ? "White" : "Black") + " is in check.";

    String turn = side ? "White to move." : "Black to move.";
    if (isFiftyMoveRule()) return turn + " The 50-move draw may be claimed.";
    if (isThreefoldRepetition()) return turn + " The threefold-repetition draw may be claimed.";
    return turn;
  }

  /**
   * Formats a move in Standard Algebraic Notation, e.g. "Nbd2", "exd5", "O-O", "e8=Q+", including
   * disambiguation derived from all other legal moves of the same piece type, and check/mate
   * suffixes verified on a copy of the board.
   */
  public String formatMove(Move move) {
    Piece piece = getPiece(move.getStart());
    if (piece == null) return move.toString();

    boolean capture = isCapture(move);
    StringBuilder notation = new StringBuilder();

    if (move.isCastling()) {
      notation.append(move.getEnd().getColumn() == 6 ? "O-O" : "O-O-O");
    } else {
      if (piece instanceof Pawn) {
        // Pawns are named only by file, and only when capturing.
        if (capture) notation.append((char) ('a' + move.getStart().getColumn()));
      } else {
        notation.append(piece.getNotationSymbol());
        Disambiguation dis = calculateDisambiguation(move, piece);
        notation.append(dis.file()).append(dis.rank());
      }

      if (capture) notation.append('x');
      notation.append(move.getEnd().toAlgebraic());

      if (move.isPromotion()) {
        notation.append('=').append(move.getPromotionPiece().getNotationSymbol());
      }
    }

    // Verify check/mate status on a copy so the real board stays untouched.
    Board copy = new Board(this);
    copy.makeMove(move);
    boolean opponent = !piece.isWhite();

    if (copy.isCheckmate(opponent)) notation.append('#');
    else if (copy.isInCheck(opponent)) notation.append('+');

    return notation.toString();
  }

  /** The file/rank letters used to disambiguate otherwise identical SAN moves. */
  private record Disambiguation(String file, String rank) {}

  /**
   * Works out which disambiguation ("Nb d2" style) a move needs by checking whether another piece
   * of the same type could legally reach the same square, and whether those alternatives share the
   * mover's file, rank or both.
   */
  private Disambiguation calculateDisambiguation(Move move, Piece movingPiece) {
    boolean sameFileCanMove = false;
    boolean sameRankCanMove = false;
    boolean anotherCanMove = false;

    for (Move alternative : getLegalMoves(movingPiece.isWhite())) {
      if (alternative.getStart().equals(move.getStart())) continue;
      if (!alternative.getEnd().equals(move.getEnd())) continue;

      Piece other = getPiece(alternative.getStart());
      if (other == null || other.getClass() != movingPiece.getClass()) continue;
      anotherCanMove = true;

      if (alternative.getStart().getColumn() == move.getStart().getColumn()) sameFileCanMove = true;
      if (alternative.getStart().getRow() == move.getStart().getRow()) sameRankCanMove = true;
    }

    if (!anotherCanMove) return new Disambiguation("", "");

    // Prefer the file when it alone distinguishes the moves; fall back to rank, then both.
    if (!sameFileCanMove) {
      return new Disambiguation(String.valueOf((char) ('a' + move.getStart().getColumn())), "");
    }
    if (!sameRankCanMove) {
      return new Disambiguation("", String.valueOf(8 - move.getStart().getRow()));
    }
    return new Disambiguation(
        String.valueOf((char) ('a' + move.getStart().getColumn())),
        String.valueOf(8 - move.getStart().getRow()));
  }

  /**
   * Builds a unique string identifying the position for repetition purposes: piece placement, side
   * to move, castling rights and the en passant square. Following FIDE rules, the en passant field
   * counts only when an actual legal en passant capture exists.
   */
  public String getPositionKey() {
    StringBuilder key = new StringBuilder();

    for (int row = 0; row < 8; row++) {
      int empty = 0;
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece == null) {
          empty++;
        } else {
          if (empty > 0) {
            key.append(empty);
            empty = 0;
          }
          key.append(piece.getSymbol());
        }
      }
      if (empty > 0) key.append(empty);
      if (row < 7) key.append('/');
    }

    key.append(' ').append(whiteToMove ? 'w' : 'b');
    key.append(' ');
    boolean anyRights = false;
    if (whiteKingsideCastle) {
      key.append('K');
      anyRights = true;
    }
    if (whiteQueensideCastle) {
      key.append('Q');
      anyRights = true;
    }
    if (blackKingsideCastle) {
      key.append('k');
      anyRights = true;
    }
    if (blackQueensideCastle) {
      key.append('q');
      anyRights = true;
    }
    if (!anyRights) key.append('-');
    key.append(' ');

    Position legalEp = getRepetitionEnPassantTarget();
    key.append(legalEp == null ? "-" : legalEp.toAlgebraic());
    return key.toString();
  }

  /**
   * Returns the en passant target only when some pawn of the side to move can actually capture en
   * passant right now; otherwise returns null so trivially unexploitable targets do not break
   * repetition detection.
   */
  private Position getRepetitionEnPassantTarget() {
    if (enPassantTarget == null) return null;

    int row = enPassantTarget.getRow();
    int column = enPassantTarget.getColumn();
    boolean side = whiteToMove;
    int pawnRow = row + (side ? 1 : -1);

    for (int sourceColumn : new int[] {column - 1, column + 1}) {
      if (!isValid(pawnRow, sourceColumn)) continue;
      Piece piece = board[pawnRow][sourceColumn];
      if (!(piece instanceof Pawn) || piece.isWhite() != side) continue;

      Position start = new Position(pawnRow, sourceColumn);
      for (Move move : getLegalMoves(start)) {
        if (move.isEnPassant() && move.getEnd().equals(enPassantTarget)) {
          return enPassantTarget;
        }
      }
    }
    return null;
  }

  /**
   * Plays a "null move": only the side to move flips. Used by the search engine for null-move
   * pruning; never a real chess move.
   */
  public void makeNullMove() {
    if (!whiteToMove) fullmoveNumber++;
    whiteToMove = !whiteToMove;
    enPassantTarget = null;
    halfmoveClock++;
    repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
    this.zobristKey = initZobristKey();
  }

  /** Serialises the position as a FEN string. */
  public String toFEN() {
    StringBuilder fen = new StringBuilder();
    for (int row = 0; row < 8; row++) {
      int empty = 0;
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece == null) {
          empty++;
        } else {
          if (empty > 0) {
            fen.append(empty);
            empty = 0;
          }
          fen.append(piece.getSymbol());
        }
      }
      if (empty > 0) fen.append(empty);
      if (row < 7) fen.append('/');
    }

    fen.append(' ').append(whiteToMove ? 'w' : 'b');
    fen.append(' ');
    boolean anyRights = false;
    if (whiteKingsideCastle) {
      fen.append('K');
      anyRights = true;
    }
    if (whiteQueensideCastle) {
      fen.append('Q');
      anyRights = true;
    }
    if (blackKingsideCastle) {
      fen.append('k');
      anyRights = true;
    }
    if (blackQueensideCastle) {
      fen.append('q');
      anyRights = true;
    }
    if (!anyRights) fen.append('-');
    fen.append(' ');
    fen.append(enPassantTarget == null ? "-" : enPassantTarget.toAlgebraic());
    fen.append(' ').append(halfmoveClock);
    fen.append(' ').append(fullmoveNumber);
    return fen.toString();
  }

  /**
   * Replaces the current state with the position described by a FEN string. Repetition tracking is
   * reset because nothing is known about earlier occurrences of the position.
   */
  public void loadFEN(String fen) {

    String[] parts = fen.trim().split("\\s+");

    if (parts.length < 4) {
      throw new IllegalArgumentException("Invalid FEN: " + fen);
    }

    clearBoard();

    parseFenPiecePlacement(parts[0]);

    whiteToMove = parts[1].equals("w");

    whiteKingsideCastle = parts[2].contains("K");
    whiteQueensideCastle = parts[2].contains("Q");
    blackKingsideCastle = parts[2].contains("k");
    blackQueensideCastle = parts[2].contains("q");

    if (parts[3].equals("-")) {
      enPassantTarget = null;
    } else {
      enPassantTarget = parseSquare(parts[3]);
    }

    halfmoveClock = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
    fullmoveNumber = parts.length >= 6 ? Integer.parseInt(parts[5]) : 1;

    repetitionCounts.clear();
    repetitionCounts.put(getPositionKey(), 1);
    this.zobristKey = initZobristKey();
  }

  /** Empties every square of the board. */
  private void clearBoard() {
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        board[row][column] = null;
      }
    }
  }

  /** Reads the piece-placement field of a FEN string onto the board. */
  private void parseFenPiecePlacement(String placement) {
    String[] ranks = placement.split("/");

    if (ranks.length != 8) {
      throw new IllegalArgumentException("Invalid FEN board: " + placement);
    }

    for (int row = 0; row < 8; row++) {
      int column = 0;

      for (char symbol : ranks[row].toCharArray()) {
        if (Character.isDigit(symbol)) {
          column += Character.getNumericValue(symbol);
        } else {
          board[row][column] = createPieceFromSymbol(symbol);
          column++;
        }
      }

      if (column != 8) {
        throw new IllegalArgumentException("Invalid FEN rank: " + ranks[row]);
      }
    }
  }

  /** Converts a single FEN piece letter into a fresh piece instance. */
  private static Piece createPieceFromSymbol(char symbol) {
    boolean white = Character.isUpperCase(symbol);
    switch (Character.toLowerCase(symbol)) {
      case 'p':
        return new Pawn(white);
      case 'n':
        return new Knight(white);
      case 'b':
        return new Bishop(white);
      case 'r':
        return new Rook(white);
      case 'q':
        return new Queen(white);
      case 'k':
        return new King(white);
      default:
        throw new IllegalArgumentException("Invalid FEN piece: " + symbol);
    }
  }

  /** Converts algebraic coordinates like "e4" into a Position. */
  private static Position parseSquare(String square) {
    int column = square.charAt(0) - 'a';
    int row = 8 - Character.getNumericValue(square.charAt(1));
    return new Position(row, column);
  }

  /** Prints the board to the console from the perspective of the given player. */
  public void printBoard(boolean playerIsWhite) {
    System.out.println();
    if (playerIsWhite) printWhiteOrientation();
    else printBlackOrientation();
    System.out.println();
  }

  /** Prints the board with White at the bottom. */
  private void printWhiteOrientation() {
    System.out.println("    a   b   c   d   e   f   g   h");
    System.out.println("  +---+---+---+---+---+---+---+---+");
    for (int row = 0; row < 8; row++) {
      System.out.print((8 - row) + " | ");
      for (int column = 0; column < 8; column++) printSquare(row, column);
      System.out.println();
      System.out.println("  +---+---+---+---+---+---+---+---+");
    }
    System.out.println("    a   b   c   d   e   f   g   h");
  }

  /** Prints the board with Black at the bottom. */
  private void printBlackOrientation() {
    System.out.println("    h   g   f   e   d   c   b   a");
    System.out.println("  +---+---+---+---+---+---+---+---+");
    for (int row = 7; row >= 0; row--) {
      System.out.print((8 - row) + " | ");
      for (int column = 7; column >= 0; column--) printSquare(row, column);
      System.out.println();
      System.out.println("  +---+---+---+---+---+---+---+---+");
    }
    System.out.println("    h   g   f   e   d   c   b   a");
  }

  /** Prints one square followed by its cell border. */
  private void printSquare(int row, int column) {
    if (board[row][column] == null) System.out.print("  | ");
    else System.out.print(board[row][column].getSymbol() + " | ");
  }
}
