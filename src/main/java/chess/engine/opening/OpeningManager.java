package chess.engine.opening;

import chess.board.Board;
import chess.board.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Opening book manager.
 *
 * The manager follows any opening line that matches the moves
 * played so far.
 *
 * For example, if the book contains:
 *
 * e4 e5 Nf3 Nc6
 * e4 c5 Nf3 d6
 * e4 e6 d4 d5
 *
 * and the game starts:
 *
 * e4
 *
 * then the manager can choose:
 *
 * e5
 * c5
 * e6
 *
 * After e4 e5, it continues from that position.
 *
 * The opening ends as soon as the current move sequence does
 * not match any opening in the book.
 */
public class OpeningManager {

    private final OpeningBook openingBook;
    private final Random random;
    private final List<String> playedMoves;

    private boolean openingActive;

    public OpeningManager() {

        this.openingBook =
                new OpeningBook();

        this.random =
                new Random();

        this.playedMoves =
                new ArrayList<>();

        this.openingActive =
                true;
    }

    /**
     * Reset the opening manager.
     *
     * This is important when reconstructing a Lichess game
     * from its complete move list.
     */
    public void reset() {

        playedMoves.clear();

        openingActive =
                true;
    }

    /**
     * Record a move played by either side.
     */
    public void recordMove(
            String moveSan
    ) {

        if (!openingActive) {
            return;
        }

        String cleanSan =
                removeCheckSuffix(moveSan);

        playedMoves.add(cleanSan);

        /*
         * Check whether the position after this move
         * is still represented by at least one opening.
         */
        if (getMatchingOpenings().isEmpty()) {

            openingActive =
                    false;

            System.out.println(
                    "Opening book: "
                            + "Opponent left opening theory."
            );
        }
    }

    /**
     * Get a legal opening move for the current position.
     */
    public Move getOpeningMove(
            Board board
    ) {

        if (!openingActive) {
            return null;
        }

        List<List<String>> matchingOpenings =
                getMatchingOpenings();

        if (matchingOpenings.isEmpty()) {

            openingActive =
                    false;

            return null;
        }

        int nextMoveIndex =
                playedMoves.size();

        List<Move> legalMoves =
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        List<Move> bookMoves =
                new ArrayList<>();

        /*
         * Find every move that continues at least
         * one matching opening.
         */
        for (List<String> opening :
                matchingOpenings) {

            if (nextMoveIndex >= opening.size()) {
                continue;
            }

            String expectedSan =
                    removeCheckSuffix(
                            opening.get(nextMoveIndex)
                    );

            for (Move move :
                    legalMoves) {

                String actualSan =
                        removeCheckSuffix(
                                board.formatMove(move)
                        );

                if (!actualSan.equals(expectedSan)) {
                    continue;
                }

                if (!containsSameMove(
                        bookMoves,
                        move
                )) {

                    bookMoves.add(move);
                }
            }
        }

        /*
         * No continuation exists.
         */
        if (bookMoves.isEmpty()) {

            openingActive =
                    false;

            System.out.println(
                    "Opening book: "
                            + "No continuation found."
            );

            return null;
        }

        /*
         * Randomly choose between the available
         * theoretical continuations.
         */
        Move selectedMove =
                bookMoves.get(
                        random.nextInt(
                                bookMoves.size()
                        )
                );

        System.out.println(
                "Opening book: "
                        + board.formatMove(
                        selectedMove
                )
                        + " ("
                        + bookMoves.size()
                        + " book moves available)"
        );

        return selectedMove;
    }

    /**
     * Check whether a move is already present.
     */
    private boolean containsSameMove(
            List<Move> moves,
            Move target
    ) {

        for (Move move : moves) {

            if (!move.getStart()
                    .equals(target.getStart())) {
                continue;
            }

            if (!move.getEnd()
                    .equals(target.getEnd())) {
                continue;
            }

            if (move.isPromotion()
                    != target.isPromotion()) {
                continue;
            }

            if (move.isPromotion()
                    && move.getPromotionPiece()
                    .getNotationSymbol()
                    != target.getPromotionPiece()
                    .getNotationSymbol()) {

                continue;
            }

            return true;
        }

        return false;
    }

    /**
     * Find every opening that begins with the moves
     * played so far.
     */
    private List<List<String>> getMatchingOpenings() {

        List<List<String>> matching =
                new ArrayList<>();

        List<List<String>> openings =
                openingBook.getOpenings();

        for (List<String> opening :
                openings) {

            if (matchesPlayedMoves(opening)) {
                matching.add(opening);
            }
        }

        return matching;
    }

    /**
     * Check whether the opening starts with all
     * moves currently played.
     */
    private boolean matchesPlayedMoves(
            List<String> opening
    ) {

        if (opening == null) {
            return false;
        }

        if (opening.size()
                < playedMoves.size()) {

            return false;
        }

        for (int i = 0;
             i < playedMoves.size();
             i++) {

            String openingMove =
                    removeCheckSuffix(
                            opening.get(i)
                    );

            String playedMove =
                    removeCheckSuffix(
                            playedMoves.get(i)
                    );

            if (!openingMove.equals(playedMove)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Disable the opening book.
     */
    public void disable() {

        openingActive =
                false;
    }

    /**
     * Whether the opening book is active.
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
     * Get a description of the current opening position.
     */
    public String getOpeningName() {

        if (!openingActive) {
            return "None / Off Theory";
        }

        if (playedMoves.isEmpty()) {
            return "Opening Book";
        }

        return "Opening Book: "
                + String.join(
                " ",
                playedMoves
        );
    }

    /**
     * Number of moves recorded.
     */
    public int getMoveIndex() {

        return playedMoves.size();
    }

    /**
     * Kept for compatibility.
     */
    public void advance() {
        // Nothing needed.
    }

    /**
     * Remove check and mate symbols.
     */
    private String removeCheckSuffix(
            String san
    ) {

        if (san == null) {
            return "";
        }

        return san
                .replace("+", "")
                .replace("#", "");
    }
}