package chess.board;

import chess.engine.MoveGenerator;
import chess.pieces.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete chess position and rules implementation.
 *
 * Coordinates use row 0..7 for ranks 8..1 and column 0..7 for files a..h.
 */
public class Board {

    private final Piece[][] board;

    private boolean whiteToMove = true;

    private boolean whiteKingsideCastle = true;
    private boolean whiteQueensideCastle = true;
    private boolean blackKingsideCastle = true;
    private boolean blackQueensideCastle = true;

    private Position enPassantTarget = null;

    /** Number of half-moves since the last pawn move or capture. */
    private int halfmoveClock = 0;

    /** Fullmove number from FEN/PGN semantics. Starts at 1 and increments after Black moves. */
    private int fullmoveNumber = 1;

    /** Position counts used for threefold/fivefold repetition. */
    private final Map<String, Integer> repetitionCounts = new HashMap<>();

    public Board() {
        board = new Piece[8][8];
        setupStartingPosition();
        repetitionCounts.put(getPositionKey(), 1);
    }

    /** Deep copy used for move simulation. History is copied so state queries remain consistent. */
    public Board(Board other) {
        this(other, true);
    }

    /**
     * Internal copy constructor. Search and legality probes do not need to duplicate the complete
     * game-history map; the search engine tracks repetitions along its current variation instead.
     */
    private Board(Board other, boolean copyHistory) {
        board = new Piece[8][8];

        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                Piece piece = other.board[row][column];
                board[row][column] = copyPiece(piece);
            }
        }

        whiteToMove = other.whiteToMove;
        whiteKingsideCastle = other.whiteKingsideCastle;
        whiteQueensideCastle = other.whiteQueensideCastle;
        blackKingsideCastle = other.blackKingsideCastle;
        blackQueensideCastle = other.blackQueensideCastle;

        if (other.enPassantTarget != null) {
            enPassantTarget = new Position(
                    other.enPassantTarget.getRow(),
                    other.enPassantTarget.getColumn()
            );
        }

        halfmoveClock = other.halfmoveClock;
        fullmoveNumber = other.fullmoveNumber;
        if (copyHistory) {
            repetitionCounts.putAll(other.repetitionCounts);
        }
    }

    private Piece copyPiece(Piece piece) {
        if (piece instanceof Pawn) return new Pawn(piece.isWhite());
        if (piece instanceof Knight) return new Knight(piece.isWhite());
        if (piece instanceof Bishop) return new Bishop(piece.isWhite());
        if (piece instanceof Rook) return new Rook(piece.isWhite());
        if (piece instanceof Queen) return new Queen(piece.isWhite());
        if (piece instanceof King) return new King(piece.isWhite());
        return null;
    }

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

    public boolean isWhiteToMove() {
        return whiteToMove;
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public int getFullmoveNumber() {
        return fullmoveNumber;
    }

    public Piece getPiece(Position position) {
        return board[position.getRow()][position.getColumn()];
    }

    public void setPiece(Position position, Piece piece) {
        board[position.getRow()][position.getColumn()] = piece;
    }

    public boolean isEmpty(Position position) {
        return getPiece(position) == null;
    }

    public boolean isEnemyPiece(Position position, boolean white) {
        Piece piece = getPiece(position);
        return piece != null && piece.isWhite() != white;
    }

    public boolean isValid(int row, int column) {
        return row >= 0 && row < 8 && column >= 0 && column < 8;
    }

    public boolean isEmpty(int row, int column) {
        return isValid(row, column) && board[row][column] == null;
    }

    public boolean hasEnemyPiece(int row, int column, boolean white) {
        return isValid(row, column)
                && board[row][column] != null
                && board[row][column].isWhite() != white;
    }

    public boolean isEnPassantTarget(Position position) {
        return enPassantTarget != null && enPassantTarget.equals(position);
    }

    public Position getEnPassantTarget() {
        return enPassantTarget;
    }

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

    /** Returns whether a square is attacked by the requested side, without generating legal moves. */
    public boolean isSquareAttacked(Position square, boolean byWhite) {
        int targetRow = square.getRow();
        int targetColumn = square.getColumn();

        // Pawns.
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

        // Kings.
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

        // Rooks / queens.
        if (lineAttacked(targetRow, targetColumn, byWhite, -1, 0, Rook.class)
                || lineAttacked(targetRow, targetColumn, byWhite, 1, 0, Rook.class)
                || lineAttacked(targetRow, targetColumn, byWhite, 0, -1, Rook.class)
                || lineAttacked(targetRow, targetColumn, byWhite, 0, 1, Rook.class)) {
            return true;
        }

        // Bishops / queens.
        return lineAttacked(targetRow, targetColumn, byWhite, -1, -1, Bishop.class)
                || lineAttacked(targetRow, targetColumn, byWhite, -1, 1, Bishop.class)
                || lineAttacked(targetRow, targetColumn, byWhite, 1, -1, Bishop.class)
                || lineAttacked(targetRow, targetColumn, byWhite, 1, 1, Bishop.class);
    }

    private boolean lineAttacked(int targetRow, int targetColumn, boolean byWhite,
                                  int rowStep, int columnStep, Class<?> slidingType) {
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

    public boolean isInCheck(boolean white) {
        Position king = findKing(white);
        return king != null && isSquareAttacked(king, !white);
    }

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

    public List<Move> getLegalMoves(Position position) {
        Piece piece = getPiece(position);
        if (piece == null) return Collections.emptyList();
        return new MoveGenerator().generateLegalMoves(piece, position, this);
    }

    /**
     * Legacy coordinate API. If a pawn is promoted, caller should use playMove with a specific Move.
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

    /** Play a legal move and update counters, rights, side-to-move and repetition state. */
    public void playMove(Move move) {
        if (move == null) throw new IllegalArgumentException("Move cannot be null.");

        Piece movingPiece = getPiece(move.getStart());
        if (movingPiece == null || movingPiece.isWhite() != whiteToMove) {
            throw new IllegalArgumentException("That piece cannot move now.");
        }

        boolean capture = isCapture(move, movingPiece);
        makeMove(move);

        // 50-move clock resets after any pawn move or capture.
        if (movingPiece instanceof Pawn || capture) halfmoveClock = 0;
        else halfmoveClock++;

        if (!whiteToMove) fullmoveNumber++;
        whiteToMove = !whiteToMove;

        repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
    }

    private boolean isCapture(Move move, Piece movingPiece) {
        return move.isEnPassant() || getPiece(move.getEnd()) != null;
    }

    /** Mutates only the board position. Used internally for simulation and by playMove. */
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

        if (movingPiece instanceof Pawn && Math.abs(end.getRow() - start.getRow()) == 2) {
            enPassantTarget = new Position(
                    (start.getRow() + end.getRow()) / 2,
                    start.getColumn()
            );
        } else {
            enPassantTarget = null;
        }
    }

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
     * Creates a new board, applies one legal move, and switches the side to move.
     * Used by the search engine so the current Board itself is never mutated.
     */
    public Board copyAndPlayMoveForSearch(Move move) {
        Board copy = new Board(this, false);
        copy.playMoveWithoutRecordingHistory(move);
        return copy;
    }

    /**
     * Lightweight copy used only to test whether a pseudo-legal move leaves its king in check.
     */
    public Board copyAndMakeMoveForValidation(Move move) {
        Board copy = new Board(this, false);
        copy.makeMove(move);
        return copy;
    }

    private void playMoveWithoutRecordingHistory(Move move) {
        Piece movingPiece = getPiece(move.getStart());
        if (movingPiece == null || movingPiece.isWhite() != whiteToMove) {
            throw new IllegalArgumentException("That piece cannot move now.");
        }

        boolean capture = isCapture(move, movingPiece);
        makeMove(move);

        if (movingPiece instanceof Pawn || capture) halfmoveClock = 0;
        else halfmoveClock++;

        if (!whiteToMove) fullmoveNumber++;
        whiteToMove = !whiteToMove;
    }

    private void removeCastlingRightForRookCapture(Position square, boolean rookWhite) {
        if (rookWhite) {
            if (square.equals(new Position(7, 0))) whiteQueensideCastle = false;
            if (square.equals(new Position(7, 7))) whiteKingsideCastle = false;
        } else {
            if (square.equals(new Position(0, 0))) blackQueensideCastle = false;
            if (square.equals(new Position(0, 7))) blackKingsideCastle = false;
        }
    }

    public boolean isCheckmate(boolean white) {
        return isInCheck(white) && getLegalMoves(white).isEmpty();
    }

    public boolean isStalemate(boolean white) {
        return !isInCheck(white) && getLegalMoves(white).isEmpty();
    }

    /** Claimable by a player under the 50-move rule. */
    public boolean isFiftyMoveRule() {
        return halfmoveClock >= 100;
    }

    /** Automatic seventy-five-move draw under FIDE rules. */
    public boolean isSeventyFiveMoveRule() {
        return halfmoveClock >= 150;
    }

    /** Number of times the exact current position has occurred. */
    public int getCurrentPositionRepetitionCount() {
        return repetitionCounts.getOrDefault(getPositionKey(), 0);
    }

    /** Claimable after the same position has occurred at least three times. */
    public boolean isThreefoldRepetition() {
        return getCurrentPositionRepetitionCount() >= 3;
    }

    /** Automatic draw after the same position occurs at least five times. */
    public boolean isFivefoldRepetition() {
        return getCurrentPositionRepetitionCount() >= 5;
    }

    /** Dead position detection for common insufficient-material cases. */
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

        // King + one bishop/knight vs king.
        if (pieces.size() == 3) {
            for (Piece p : pieces) {
                if (p instanceof Bishop || p instanceof Knight) return true;
            }
            return false;
        }

        // King + bishop vs king + bishop, where bishops stay on the same color.
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

            if (allMinors && whiteBishops == 1 && blackBishops == 1
                    && whiteBishopSquare != null && blackBishopSquare != null) {
                int whiteColor = (whiteBishopSquare.getRow() + whiteBishopSquare.getColumn()) & 1;
                int blackColor = (blackBishopSquare.getRow() + blackBishopSquare.getColumn()) & 1;
                return whiteColor == blackColor;
            }
        }

        return false;
    }

    /**
     * Returns a human-readable status. Game-ending automatic draws are prioritized first.
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
     * SAN formatter. It calculates disambiguation from all other LEGAL moves by same piece type.
     */
    public String formatMove(Move move) {
        Piece piece = getPiece(move.getStart());
        if (piece == null) return move.toString();

        boolean capture = isCapture(move, piece);
        StringBuilder notation = new StringBuilder();

        if (move.isCastling()) {
            notation.append(move.getEnd().getColumn() == 6 ? "O-O" : "O-O-O");
        } else {
            if (piece instanceof Pawn) {
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

        Board copy = new Board(this);
        copy.makeMove(move);
        boolean opponent = !piece.isWhite();

        if (copy.isCheckmate(opponent)) notation.append('#');
        else if (copy.isInCheck(opponent)) notation.append('+');

        return notation.toString();
    }

    private record Disambiguation(String file, String rank) {}

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

        // SAN: use file if files alone distinguish; otherwise use rank.
        if (!sameFileCanMove) {
            return new Disambiguation(String.valueOf((char) ('a' + move.getStart().getColumn())), "");
        }
        if (!sameRankCanMove) {
            return new Disambiguation("", String.valueOf(8 - move.getStart().getRow()));
        }
        // If both file and rank collide conceptually, both are required.
        return new Disambiguation(
                String.valueOf((char) ('a' + move.getStart().getColumn())),
                String.valueOf(8 - move.getStart().getRow())
        );
    }

    /**
     * Position key for repetition. The en-passant field is included only when an actual legal
     * en-passant capture is possible, matching FIDE position identity.
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
        if (whiteKingsideCastle) { key.append('K'); anyRights = true; }
        if (whiteQueensideCastle) { key.append('Q'); anyRights = true; }
        if (blackKingsideCastle) { key.append('k'); anyRights = true; }
        if (blackQueensideCastle) { key.append('q'); anyRights = true; }
        if (!anyRights) key.append('-');
        key.append(' ');

        Position legalEp = getRepetitionEnPassantTarget();
        key.append(legalEp == null ? "-" : legalEp.toAlgebraic());
        return key.toString();
    }

    /** A defensive copy of the real-game history used to seed a new search. */
    public Map<String, Integer> getRepetitionCountsSnapshot() {
        return new HashMap<>(repetitionCounts);
    }

    /**
     * Fast deterministic key for the transposition table. The half-move clock is included because
     * it can change whether a position is drawn even when all pieces occupy the same squares.
     */
    public long getSearchKey() {
        long key = 0xcbf29ce484222325L;

        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                Piece piece = board[row][column];
                if (piece == null) continue;

                long value = ((long) piece.getSymbol() << 8) | (row * 8L + column);
                key ^= value;
                key *= 0x100000001b3L;
            }
        }

        key ^= whiteToMove ? 0x9e3779b97f4a7c15L : 0xc2b2ae3d27d4eb4fL;

        int castlingMask = 0;
        if (whiteKingsideCastle) castlingMask |= 1;
        if (whiteQueensideCastle) castlingMask |= 2;
        if (blackKingsideCastle) castlingMask |= 4;
        if (blackQueensideCastle) castlingMask |= 8;
        key ^= 0x94d049bb133111ebL * (castlingMask + 1L);

        if (enPassantTarget != null) {
            key ^= 0x2545f4914f6cdd1dL * (enPassantTarget.getColumn() + 1L);
        }

        key ^= 0x165667b19e3779f9L * (Math.min(halfmoveClock, 150) + 1L);
        return key;
    }

    private Position getRepetitionEnPassantTarget() {
        if (enPassantTarget == null) return null;

        int row = enPassantTarget.getRow();
        int column = enPassantTarget.getColumn();
        boolean side = whiteToMove;
        int pawnRow = row + (side ? 1 : -1);

        for (int sourceColumn : new int[]{column - 1, column + 1}) {
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

    public String toFEN() {
        StringBuilder fen = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            int empty = 0;
            for (int column = 0; column < 8; column++) {
                Piece piece = board[row][column];
                if (piece == null) {
                    empty++;
                } else {
                    if (empty > 0) { fen.append(empty); empty = 0; }
                    fen.append(piece.getSymbol());
                }
            }
            if (empty > 0) fen.append(empty);
            if (row < 7) fen.append('/');
        }

        fen.append(' ').append(whiteToMove ? 'w' : 'b');
        fen.append(' ');
        boolean anyRights = false;
        if (whiteKingsideCastle) { fen.append('K'); anyRights = true; }
        if (whiteQueensideCastle) { fen.append('Q'); anyRights = true; }
        if (blackKingsideCastle) { fen.append('k'); anyRights = true; }
        if (blackQueensideCastle) { fen.append('q'); anyRights = true; }
        if (!anyRights) fen.append('-');
        fen.append(' ');
        fen.append(enPassantTarget == null ? "-" : enPassantTarget.toAlgebraic());
        fen.append(' ').append(halfmoveClock);
        fen.append(' ').append(fullmoveNumber);
        return fen.toString();
    }

    public void loadFEN(String fen) {

        String[] parts = fen.trim().split("\\s+");

        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "Invalid FEN: " + fen
            );
        }

        /*
         * Clear board.
         */
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                board[row][column] = null;
            }
        }

        /*
         * Piece placement.
         */
        String[] ranks = parts[0].split("/");

        if (ranks.length != 8) {
            throw new IllegalArgumentException(
                    "Invalid FEN board: " + parts[0]
            );
        }

        for (int row = 0; row < 8; row++) {

            int column = 0;

            for (char symbol : ranks[row].toCharArray()) {

                if (Character.isDigit(symbol)) {

                    column +=
                            Character.getNumericValue(symbol);

                } else {

                    boolean white =
                            Character.isUpperCase(symbol);

                    Piece piece;

                    switch (Character.toLowerCase(symbol)) {

                        case 'p' -> piece = new Pawn(white);
                        case 'n' -> piece = new Knight(white);
                        case 'b' -> piece = new Bishop(white);
                        case 'r' -> piece = new Rook(white);
                        case 'q' -> piece = new Queen(white);
                        case 'k' -> piece = new King(white);

                        default -> throw new IllegalArgumentException(
                                "Invalid FEN piece: " + symbol
                        );
                    }

                    board[row][column] = piece;
                    column++;
                }
            }

            if (column != 8) {
                throw new IllegalArgumentException(
                        "Invalid FEN rank: " + ranks[row]
                );
            }
        }

        /*
         * Side to move.
         */
        whiteToMove =
                parts[1].equals("w");

        /*
         * Castling rights.
         */
        whiteKingsideCastle =
                parts[2].contains("K");

        whiteQueensideCastle =
                parts[2].contains("Q");

        blackKingsideCastle =
                parts[2].contains("k");

        blackQueensideCastle =
                parts[2].contains("q");

        /*
         * En passant.
         */
        if (parts[3].equals("-")) {

            enPassantTarget = null;

        } else {

            String square = parts[3];

            int column =
                    square.charAt(0) - 'a';

            int row =
                    8 - Character.getNumericValue(
                            square.charAt(1)
                    );

            enPassantTarget =
                    new Position(row, column);
        }

        /*
         * Halfmove clock.
         */
        if (parts.length >= 5) {
            halfmoveClock =
                    Integer.parseInt(parts[4]);
        } else {
            halfmoveClock = 0;
        }

        /*
         * Fullmove number.
         */
        if (parts.length >= 6) {
            fullmoveNumber =
                    Integer.parseInt(parts[5]);
        } else {
            fullmoveNumber = 1;
        }

        /*
         * Reset repetition tracking.
         */
        repetitionCounts.clear();
        repetitionCounts.put(
                getPositionKey(),
                1
        );
    }

    public void printBoard(boolean playerIsWhite) {
        System.out.println();
        if (playerIsWhite) printWhiteOrientation();
        else printBlackOrientation();
        System.out.println();
    }

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

    private void printSquare(int row, int column) {
        if (board[row][column] == null) System.out.print("  | ");
        else System.out.print(board[row][column].getSymbol() + " | ");
    }
}
