package chess.engine.opening;

import chess.board.Board;
import chess.board.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Opening book manager supporting both White and Black.
 *
 * Uses the opening book while the current position is in theory.
 * If the position leaves the opening book, the book is disabled and
 * the normal search engine should take over.
 *
 * The manager does not commit to one opening line. Instead, it finds
 * all book moves that are valid for the current position and randomly
 * chooses between them.
 */
public class OpeningManager {

    private final OpeningBook openingBook;
    private final Random random;
    private final List<String> playedMoves;

    private boolean openingActive;

    public OpeningManager() {
        this.openingBook = new OpeningBook();
        this.random = new Random();
        this.playedMoves = new ArrayList<>();
        this.openingActive = true;
    }

    /**
     * Record a move played on the board.
     *
     * This should be called for EVERY move played by either side.
     *
     * @param moveSan SAN representation of the move
     */
    public void recordMove(String moveSan) {
        if (!openingActive) {
            return;
        }

        String cleanSan = removeCheckSuffix(moveSan);
        playedMoves.add(cleanSan);

        /*
         * Check whether the current position is still represented
         * somewhere in the opening book.
         */
        if (getMatchingOpenings().isEmpty()) {
            System.out.println(
                    "Opening book: Position is off theory. "
                            + "Switching to normal engine search."
            );

            openingActive = false;
        }
    }

    /**
     * Gets a legal opening move for the current position.
     *
     * The manager searches through every opening that matches the
     * moves played so far and collects all legal book moves.
     *
     * If there are several book moves, one is chosen randomly.
     *
     * If there are no book moves, the opening book is disabled and
     * null is returned so the normal search engine can choose a move.
     *
     * @param board current board
     * @return a legal opening move, or null if the engine should search
     */
    public Move getOpeningMove(Board board) {

        if (!openingActive) {
            return null;
        }

        List<List<String>> matchingOpenings = getMatchingOpenings();

        if (matchingOpenings.isEmpty()) {
            System.out.println(
                    "Opening book: No matching theory. "
                            + "Switching to normal engine search."
            );

            openingActive = false;
            return null;
        }

        List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

        /*
         * Find every unique legal move that appears as the next move
         * in at least one matching opening.
         */
        List<Move> bookMoves = new ArrayList<>();

        for (List<String> opening : matchingOpenings) {

            int nextMoveIndex = playedMoves.size();

            if (nextMoveIndex >= opening.size()) {
                continue;
            }

            String expectedSan =
                    removeCheckSuffix(opening.get(nextMoveIndex));

            for (Move move : legalMoves) {

                String actualSan =
                        removeCheckSuffix(board.formatMove(move));

                if (actualSan.equals(expectedSan)) {

                    if (!containsSameMove(bookMoves, move, board)) {
                        bookMoves.add(move);
                    }
                }
            }
        }

        /*
         * No legal book moves means we have reached the end of theory
         * or the book does not contain this position.
         */
        if (bookMoves.isEmpty()) {
            System.out.println(
                    "Opening book: No book move available. "
                            + "Switching to normal engine search."
            );

            openingActive = false;
            return null;
        }

        /*
         * Randomly choose between the available book moves.
         *
         * This is what prevents the engine from following the exact
         * same opening line every time.
         */
        Move selectedMove =
                bookMoves.get(random.nextInt(bookMoves.size()));

        System.out.println(
                "Opening book: "
                        + board.formatMove(selectedMove)
                        + " ("
                        + bookMoves.size()
                        + " book moves available)"
        );

        return selectedMove;
    }

    /**
     * Checks whether a move is already in the list.
     */
    private boolean containsSameMove(
            List<Move> moves,
            Move target,
            Board board
    ) {
        String targetSan =
                removeCheckSuffix(board.formatMove(target));

        for (Move move : moves) {

            String moveSan =
                    removeCheckSuffix(board.formatMove(move));

            if (moveSan.equals(targetSan)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns every opening line that matches the moves played so far.
     */
    private List<List<String>> getMatchingOpenings() {

        List<List<String>> matching = new ArrayList<>();

        List<List<String>> openings =
                openingBook.getOpenings();

        if (openings == null) {
            return matching;
        }

        for (List<String> opening : openings) {

            if (matchesPlayedMoves(opening)) {
                matching.add(opening);
            }
        }

        return matching;
    }

    /**
     * Checks whether an opening contains all moves played so far.
     */
    private boolean matchesPlayedMoves(List<String> opening) {

        if (opening == null) {
            return false;
        }

        if (opening.size() < playedMoves.size()) {
            return false;
        }

        for (int i = 0; i < playedMoves.size(); i++) {

            String openingMove =
                    removeCheckSuffix(opening.get(i));

            String playedMove =
                    removeCheckSuffix(playedMoves.get(i));

            if (!openingMove.equals(playedMove)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Disables the opening book manually.
     */
    public void disable() {

        if (openingActive) {
            openingActive = false;

            System.out.println(
                    "Opening book disabled. "
                            + "Normal engine search will be used."
            );
        }
    }

    /**
     * Returns whether the opening book is still active.
     */
    public boolean isOpeningActive() {
        return openingActive;
    }

    /**
     * Backwards compatibility.
     */
    public boolean isActive() {
        return isOpeningActive();
    }

    /**
     * Backwards compatibility.
     *
     * The old implementation selected a specific opening line.
     * This implementation no longer does that, so this returns a
     * description of the current book position instead.
     */
    public String getOpeningName() {

        if (!openingActive) {
            return "None / Off Theory";
        }

        if (playedMoves.isEmpty()) {
            return "Opening Book";
        }

        return "Opening Book: "
                + String.join(" ", playedMoves);
    }

    /**
     * Returns the number of moves currently recorded.
     */
    public int getMoveIndex() {
        return playedMoves.size();
    }

    /**
     * Kept for compatibility with existing code.
     *
     * The opening manager automatically advances based on
     * playedMoves, so nothing needs to happen here.
     */
    public void advance() {
        // Nothing needed
    }

    /**
     * Removes check and mate suffixes so that book moves can be
     * compared consistently.
     */
    private String removeCheckSuffix(String san) {

        if (san == null) {
            return "";
        }

        return san
                .replace("+", "")
                .replace("#", "");
    }
}