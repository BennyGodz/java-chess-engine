package chess.board;

import chess.engine.MoveGenerator;
import chess.pieces.*;
import java.util.ArrayList;
import java.util.Arrays;
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

  private static final int[][] KNIGHT_OFFSETS = {
    {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}
  };
  private static final int[][] STRAIGHT_DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
  private static final int[][] DIAGONAL_DIRECTIONS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

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

  // Random but fixed Zobrist keys, one per coloured piece/square, castling right set and en passant
  // square.
  private static final long[][] PIECE_KEYS;
  private static final long[] CASTLING_KEYS;
  private static final long[] EN_PASSANT_KEYS;
  private static final long SIDE_TO_MOVE_KEY;

  static {
    java.util.Random rand = new java.util.Random(0);
    PIECE_KEYS = new long[12][64];
    CASTLING_KEYS = new long[16];
    EN_PASSANT_KEYS = new long[64];
    for (int p = 0; p < 12; p++) {
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
   * Computes the Zobrist hash for the current position by XOR-ing together the keys of every piece,
   * the castling rights, the en passant square and the side to move.
   */
  private long initZobristKey() {
    long key = 0;
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece != null) key ^= PIECE_KEYS[pieceTypeIndex(piece)][row * 8 + column];
      }
    }
    int castling = 0;
    if (whiteKingsideCastle) castling |= 8;
    if (whiteQueensideCastle) castling |= 4;
    if (blackKingsideCastle) castling |= 2;
    if (blackQueensideCastle) castling |= 1;
    key ^= CASTLING_KEYS[castling];
    if (enPassantTarget != null) {
      key ^= EN_PASSANT_KEYS[enPassantTarget.getRow() * 8 + enPassantTarget.getColumn()];
    }
    return whiteToMove ? key : key ^ SIDE_TO_MOVE_KEY;
  }

  /**
   * Maps a piece to its Zobrist table index. White occupies 0..5 and Black occupies 6..11, so
   * otherwise identical positions with colours swapped can never share a transposition key.
   */
  private static int pieceTypeIndex(Piece piece) {
    int type;
    if (piece instanceof Pawn) type = 0;
    else if (piece instanceof Knight) type = 1;
    else if (piece instanceof Bishop) type = 2;
    else if (piece instanceof Rook) type = 3;
    else if (piece instanceof Queen) type = 4;
    else type = 5;
    return type + (piece.isWhite() ? 0 : 6);
  }

  /**
   * Deep copy of another board, used to simulate moves without touching the original. History is
   * copied so state queries on the copy stay consistent.
   */
  public Board(Board other) {
    this(other, true);
  }

  /** Internal lightweight copy used when repetition history is irrelevant. */
  private Board(Board other, boolean copyHistory) {
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

    enPassantTarget = other.enPassantTarget;
    halfmoveClock = other.halfmoveClock;
    fullmoveNumber = other.fullmoveNumber;
    if (copyHistory) repetitionCounts.putAll(other.repetitionCounts);
    zobristKey = other.zobristKey;
  }

  /** Creates a fresh instance of the given piece's exact type. */
  private Piece copyPiece(Piece piece) {
    return piece == null ? null : createPieceFromSymbol(piece.getSymbol());
  }

  /** Places all 32 pieces on their starting squares. */
  private void setupStartingPosition() {
    setupBackRank(0, false);
    for (int column = 0; column < 8; column++) {
      board[1][column] = new Pawn(false);
      board[6][column] = new Pawn(true);
    }
    setupBackRank(7, true);
  }

  private void setupBackRank(int row, boolean white) {
    board[row] =
        new Piece[] {
          new Rook(white),
          new Knight(white),
          new Bishop(white),
          new Queen(white),
          new King(white),
          new Bishop(white),
          new Knight(white),
          new Rook(white)
        };
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

  /** Returns whether an enemy piece (from white's point of view) stands on the square. */
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

  /** Returns whether the square holds an enemy piece from white's point of view. */
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
    boolean rights = white ? whiteKingsideCastle : blackKingsideCastle;
    return canCastle(white, rights, 7, new int[] {5, 6}, new int[] {4, 5, 6});
  }

  /**
   * Returns whether the side may castle queenside: rights intact, king and rook on their home
   * squares, squares between them empty and none of the king's path attacked.
   */
  public boolean canCastleQueenside(boolean white) {
    boolean rights = white ? whiteQueensideCastle : blackQueensideCastle;
    return canCastle(white, rights, 0, new int[] {1, 2, 3}, new int[] {4, 3, 2});
  }

  private boolean canCastle(
      boolean white, boolean rights, int rookColumn, int[] emptyColumns, int[] safeColumns) {
    if (!rights) return false;
    int row = white ? 7 : 0;
    Piece king = board[row][4];
    Piece rook = board[row][rookColumn];
    if (!(king instanceof King)
        || king.isWhite() != white
        || !(rook instanceof Rook)
        || rook.isWhite() != white) return false;
    for (int column : emptyColumns) if (!isEmpty(row, column)) return false;
    for (int column : safeColumns) {
      if (isSquareAttacked(new Position(row, column), !white)) return false;
    }
    return true;
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
   * Returns whether a square is attacked by any piece of the given color. Checks pawns, knights,
   * kings and sliding pieces directly instead of generating legal moves, which makes it fast enough
   * for check detection during search.
   */
  public boolean isSquareAttacked(Position square, boolean byWhite) {
    int targetRow = square.getRow();
    int targetColumn = square.getColumn();

    // Pawns attack diagonally "backwards" relative to their movement direction.
    int pawnRow = targetRow + (byWhite ? 1 : -1);
    for (int offset : new int[] {-1, 1}) {
      if (hasPiece(pawnRow, targetColumn + offset, byWhite, Pawn.class)) return true;
    }

    for (int[] offset : KNIGHT_OFFSETS) {
      if (hasPiece(targetRow + offset[0], targetColumn + offset[1], byWhite, Knight.class)) {
        return true;
      }
    }

    // Adjacent kings.
    for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
      for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
        if (rowOffset == 0 && columnOffset == 0) continue;
        int row = targetRow + rowOffset;
        int column = targetColumn + columnOffset;
        if (hasPiece(row, column, byWhite, King.class)) return true;
      }
    }

    return linesAttacked(targetRow, targetColumn, byWhite, STRAIGHT_DIRECTIONS, Rook.class)
        || linesAttacked(targetRow, targetColumn, byWhite, DIAGONAL_DIRECTIONS, Bishop.class);
  }

  private boolean hasPiece(int row, int column, boolean white, Class<?> type) {
    if (!isValid(row, column)) return false;
    Piece piece = board[row][column];
    return type.isInstance(piece) && piece.isWhite() == white;
  }

  private boolean linesAttacked(
      int row, int column, boolean white, int[][] directions, Class<?> type) {
    for (int[] direction : directions) {
      if (lineAttacked(row, column, white, direction[0], direction[1], type)) return true;
    }
    return false;
  }

  /**
   * Walks outward from a square in one direction until a piece is found; returns whether that first
   * piece is an enemy slider able to attack along this direction (a rook-type or queen).
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
   * Legacy coordinate API: plays a non-promotion move chosen only by its start and end squares. For
   * promotions callers must use #playMove(Move) with a specific Move.
   *
   * @return whether a matching legal move existed and was played
   */
  public boolean movePiece(Position start, Position end) {
    Piece piece = getPiece(start);
    if (piece == null || piece.isWhite() != whiteToMove) return false;
    Move selected = findLegalMove(start, end, '\0');
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

      if (move.getPromotionPiece().getNotationSymbol() == Character.toUpperCase(promotionChoice))
        return move;
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
   * Counters and repetition state are handled by #playMove; this method alone is used for
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

  /** Creates a lightweight position copy for testing whether a move exposes its own king. */
  public Board copyAndMakeMoveForValidation(Move move) {
    Board copy = new Board(this, false);
    copy.makeMove(move);
    return copy;
  }

  /** Creates the history-free synthetic position used for null-move pruning. */
  public Board copyAndMakeNullMoveForSearch() {
    Board copy = new Board(this, false);
    copy.makeNullMove();
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

  public boolean isAutomaticDraw() {
    return isStalemate(whiteToMove)
        || isSeventyFiveMoveRule()
        || isFivefoldRepetition()
        || isInsufficientMaterial();
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
    StringBuilder key = new StringBuilder(piecePlacement());
    key.append(' ')
        .append(whiteToMove ? 'w' : 'b')
        .append(' ')
        .append(castlingRights())
        .append(' ');
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
   * Plays a synthetic null move for pruning. It flips the side and clears en passant, but it does
   * not alter real-game clocks or repetition history because no such move occurred in the game.
   */
  public void makeNullMove() {
    whiteToMove = !whiteToMove;
    enPassantTarget = null;
    repetitionCounts.clear();
    this.zobristKey = initZobristKey();
  }

  /** Serialises the position as a FEN string. */
  public String toFEN() {
    return piecePlacement()
        + " "
        + (whiteToMove ? 'w' : 'b')
        + " "
        + castlingRights()
        + " "
        + (enPassantTarget == null ? "-" : enPassantTarget.toAlgebraic())
        + " "
        + halfmoveClock
        + " "
        + fullmoveNumber;
  }

  private String piecePlacement() {
    StringBuilder placement = new StringBuilder();
    for (int row = 0; row < 8; row++) {
      int empty = 0;
      for (int column = 0; column < 8; column++) {
        Piece piece = board[row][column];
        if (piece == null) {
          empty++;
        } else {
          if (empty > 0) {
            placement.append(empty);
            empty = 0;
          }
          placement.append(piece.getSymbol());
        }
      }
      if (empty > 0) placement.append(empty);
      if (row < 7) placement.append('/');
    }
    return placement.toString();
  }

  private String castlingRights() {
    String rights =
        (whiteKingsideCastle ? "K" : "")
            + (whiteQueensideCastle ? "Q" : "")
            + (blackKingsideCastle ? "k" : "")
            + (blackQueensideCastle ? "q" : "");
    return rights.isEmpty() ? "-" : rights;
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
    for (Piece[] row : board) Arrays.fill(row, null);
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
    return switch (Character.toLowerCase(symbol)) {
      case 'p' -> new Pawn(white);
      case 'n' -> new Knight(white);
      case 'b' -> new Bishop(white);
      case 'r' -> new Rook(white);
      case 'q' -> new Queen(white);
      case 'k' -> new King(white);
      default -> throw new IllegalArgumentException("Invalid FEN piece: " + symbol);
    };
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
    String files =
        playerIsWhite ? "    a   b   c   d   e   f   g   h" : "    h   g   f   e   d   c   b   a";
    System.out.println(files);
    System.out.println("  +---+---+---+---+---+---+---+---+");
    int start = playerIsWhite ? 0 : 7;
    int step = playerIsWhite ? 1 : -1;
    for (int row = start; row >= 0 && row < 8; row += step) {
      System.out.print((8 - row) + " | ");
      for (int column = start; column >= 0 && column < 8; column += step) {
        printSquare(row, column);
      }
      System.out.println();
      System.out.println("  +---+---+---+---+---+---+---+---+");
    }
    System.out.println(files);
    System.out.println();
  }

  /** Prints one square followed by its cell border. */
  private void printSquare(int row, int column) {
    if (board[row][column] == null) System.out.print("  | ");
    else System.out.print(board[row][column].getSymbol() + " | ");
  }
}
