package chess.engine.opening;

import chess.board.Board;
import chess.board.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Opening book manager.
 *
 * The opening is only a suggested line.
 *
 * The engine is allowed to reject the book move if
 * the opponent has played something that makes the
 * book move strategically or tactically bad.
 */
public class OpeningManager {

    private final OpeningBook openingBook;
    private final Random random;

    private List<String> selectedOpening;
    private int moveIndex;

    /*
     * Once the opponent deviates badly from the opening,
     * the opening book is permanently disabled for this game.
     */
    private boolean openingActive;

    public OpeningManager() {
        this.openingBook = new OpeningBook();
        this.random = new Random();

        selectRandomOpening();
    }

    /**
     * Select a random opening.
     */
    private void selectRandomOpening() {

        List<List<String>> openings =
                openingBook.getOpenings();

        if (openings == null || openings.isEmpty()) {

            selectedOpening = new ArrayList<>();
            moveIndex = 0;
            openingActive = false;

            return;
        }

        selectedOpening =
                openings.get(
                        random.nextInt(openings.size())
                );

        moveIndex = 0;
        openingActive = true;

        System.out.println();
        System.out.println("================================");
        System.out.println("OPENING BOOK");
        System.out.println("================================");

        System.out.println(
                "Selected opening: "
                        + String.join(
                        " ",
                        selectedOpening
                )
        );

        System.out.println();
    }

    /**
     * Get the opening move expected in the current position.
     *
     * Returns null if:
     *
     * 1. The opening has ended.
     * 2. The current position does not match the book.
     */
    public Move getOpeningMove(Board board) {

        if (!openingActive) {
            return null;
        }

        if (selectedOpening == null ||
                moveIndex >= selectedOpening.size()) {

            openingActive = false;
            return null;
        }

        String expectedSan =
                selectedOpening.get(moveIndex);

        List<Move> legalMoves =
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        List<Move> matchingMoves =
                new ArrayList<>();

        String cleanExpected =
                removeCheckSuffix(expectedSan);

        for (Move move : legalMoves) {

            String actualSan =
                    board.formatMove(move);

            String cleanActual =
                    removeCheckSuffix(actualSan);

            if (cleanActual.equals(cleanExpected)) {
                matchingMoves.add(move);
            }
        }

        /*
         * If the current position does not allow
         * the expected opening move, the opponent
         * has deviated from our book.
         */
        if (matchingMoves.size() != 1) {

            System.out.println();
            System.out.println(
                    "Opening deviation detected."
            );

            System.out.println(
                    "Expected book move: "
                            + expectedSan
            );

            System.out.println(
                    "Opening book disabled."
            );

            openingActive = false;

            return null;
        }

        return matchingMoves.get(0);
    }

    /**
     * Advance to the next book move.
     */
    public void advance() {

        if (!openingActive) {
            return;
        }

        moveIndex++;

        if (moveIndex >= selectedOpening.size()) {

            openingActive = false;

            System.out.println(
                    "Opening book finished."
            );
        }
    }

    /**
     * Permanently disable the opening book.
     */
    public void disable() {

        openingActive = false;

        System.out.println(
                "Opening book disabled. "
                        + "Engine will play normally."
        );
    }

    /**
     * Is the opening still active?
     */
    public boolean isOpeningActive() {
        return openingActive
                && selectedOpening != null
                && moveIndex < selectedOpening.size();
    }

    /**
     * Alias for compatibility with older code.
     */
    public boolean isActive() {
        return isOpeningActive();
    }

    /**
     * Get the selected opening line.
     */
    public String getOpeningName() {

        if (selectedOpening == null ||
                selectedOpening.isEmpty()) {

            return "Unknown";
        }

        return String.join(
                " ",
                selectedOpening
        );
    }

    /**
     * Get current opening move index.
     */
    public int getMoveIndex() {
        return moveIndex;
    }

    /**
     * Remove check/checkmate markers when
     * comparing SAN moves.
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