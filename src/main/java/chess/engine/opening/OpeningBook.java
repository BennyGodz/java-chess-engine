package chess.engine.opening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collection of opening lines for the chess engine.
 *
 * Moves are stored in SAN notation.
 *
 * The OpeningManager selects one line and follows it until
 * the opponent leaves that opening line.
 */
public class OpeningBook {

    private final List<List<String>> openings =
            new ArrayList<>();

    public OpeningBook() {

        /*
         * =========================================================
         * KING'S PAWN OPENINGS
         * =========================================================
         */

        /*
         * Italian Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bc4", "Bc5",
                "c3", "Nf6",
                "d3", "d6",
                "O-O", "O-O"
        ));

        /*
         * Italian Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bc4", "Bc5",
                "c3", "Nf6",
                "d4", "exd4",
                "cxd4"
        ));

        /*
         * Ruy Lopez
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bb5", "a6",
                "Ba4", "Nf6",
                "O-O", "Be7",
                "Re1", "b5"
        ));

        /*
         * Ruy Lopez
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bb5", "a6",
                "Ba4", "Nf6",
                "O-O", "Be7",
                "d3", "b5"
        ));

        /*
         * Scotch Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "d4", "exd4",
                "Nxd4", "Nf6",
                "Nc3", "Bb4"
        ));

        /*
         * Scotch Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "d4", "exd4",
                "Nxd4", "Bc5",
                "Be3", "Nf6"
        ));

        /*
         * Four Knights
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Nc3", "Nf6",
                "Bb5", "Bb4",
                "O-O", "O-O"
        ));

        /*
         * Vienna Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nc3", "Nf6",
                "Nf3", "Nc6",
                "Bb5", "Bb4",
                "O-O", "O-O"
        ));

        /*
         * King's Gambit
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "f4", "exf4",
                "Nf3", "g5",
                "h4", "g4",
                "Ne5", "Nf6"
        ));

        /*
         * Pirc Defense
         */
        openings.add(Arrays.asList(
                "e4", "d6",
                "d4", "Nf6",
                "Nc3", "g6",
                "Nf3", "Bg7",
                "Be2", "O-O"
        ));

        /*
         * Modern Defense
         */
        openings.add(Arrays.asList(
                "e4", "g6",
                "d4", "Bg7",
                "Nc3", "d6",
                "Nf3", "Nf6",
                "Be2", "O-O"
        ));

        /*
         * =========================================================
         * SICILIAN DEFENSE
         * ========================================================= */

        /*
         * Open Sicilian
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "d6",
                "d4", "cxd4",
                "Nxd4", "Nf6",
                "Nc3", "g6"
        ));

        /*
         * Sicilian
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "Nc6",
                "d4", "cxd4",
                "Nxd4", "Nf6",
                "Nc3", "d6"
        ));

        /*
         * Accelerated Dragon
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "Nc6",
                "d4", "cxd4",
                "Nxd4", "g6",
                "Nc3", "Bg7"
        ));

        /*
         * Sicilian Classical
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "Nc6",
                "d4", "cxd4",
                "Nxd4", "Nf6",
                "Nc3", "e5"
        ));

        /*
         * Sicilian
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "e6",
                "d4", "cxd4",
                "Nxd4", "Nc6",
                "Nc3", "Nf6"
        ));

        /*
         * =========================================================
         * FRENCH DEFENSE
         * ========================================================= */

        /*
         * French Defense
         */
        openings.add(Arrays.asList(
                "e4", "e6",
                "d4", "d5",
                "Nc3", "Nf6",
                "Bg5", "Bb4",
                "e5", "h6"
        ));

        /*
         * French Defense
         */
        openings.add(Arrays.asList(
                "e4", "e6",
                "d4", "d5",
                "Nc3", "Nf6",
                "e5", "Nfd7",
                "f4", "c5"
        ));

        /*
         * =========================================================
         * CARO KANN
         * ========================================================= */

        /*
         * Caro Kann Classical
         */
        openings.add(Arrays.asList(
                "e4", "c6",
                "d4", "d5",
                "Nc3", "dxe4",
                "Nxe4", "Bf5",
                "Ng3", "Bg6"
        ));

        /*
         * Caro Kann
         */
        openings.add(Arrays.asList(
                "e4", "c6",
                "d4", "d5",
                "Nd2", "dxe4",
                "Nxe4", "Bf5",
                "Ng3", "Bg6"
        ));

        /*
         * =========================================================
         * SCANDINAVIAN
         * ========================================================= */

        openings.add(Arrays.asList(
                "e4", "d5",
                "exd5", "Qxd5",
                "Nc3", "Qd8",
                "d4", "Nf6",
                "Nf3", "c6"
        ));

        /*
         * Scandinavian
         */
        openings.add(Arrays.asList(
                "e4", "d5",
                "exd5", "Nf6",
                "d4", "Nxd5",
                "Nf3", "c6",
                "c4", "Nb6"
        ));

        /*
         * =========================================================
         * QUEEN'S PAWN OPENINGS
         * ========================================================= */

        /*
         * Queen's Gambit
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "e6",
                "Nc3", "Nf6",
                "Bg5", "Be7",
                "e3", "O-O"
        ));

        /*
         * Queen's Gambit Declined
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "e6",
                "Nc3", "Nf6",
                "Bg5", "Be7",
                "Nf3", "O-O"
        ));

        /*
         * Queen's Gambit Accepted
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "dxc4",
                "Nf3", "Nf6",
                "e3", "e6",
                "Bxc4", "c5"
        ));

        /*
         * Slav Defense
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "c6",
                "Nf3", "Nf6",
                "Nc3", "dxc4",
                "a4", "Bf5"
        ));

        /*
         * Semi Slav
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "c6",
                "Nf3", "Nf6",
                "Nc3", "e6",
                "e3", "Nbd7"
        ));

        /*
         * King's Indian Defense
         */
        openings.add(Arrays.asList(
                "d4", "Nf6",
                "c4", "g6",
                "Nc3", "Bg7",
                "e4", "d6",
                "Nf3", "O-O"
        ));

        /*
         * Nimzo Indian Defense
         */
        openings.add(Arrays.asList(
                "d4", "Nf6",
                "c4", "e6",
                "Nc3", "Bb4",
                "e3", "O-O",
                "Bd3", "d5"
        ));

        /*
         * Queen's Indian Defense
         */
        openings.add(Arrays.asList(
                "d4", "Nf6",
                "c4", "e6",
                "Nf3", "b6",
                "g3", "Bb7",
                "Bg2", "Be7"
        ));

        /*
         * Catalan
         */
        openings.add(Arrays.asList(
                "d4", "Nf6",
                "c4", "e6",
                "g3", "d5",
                "Bg2", "Be7",
                "Nf3", "O-O"
        ));

        /*
         * London System
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "Nf3", "Nf6",
                "Bf4", "e6",
                "e3", "Bd6",
                "Bg3", "O-O"
        ));

        /*
         * Colle System
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "Nf3", "Nf6",
                "e3", "e6",
                "Bd3", "Bd6",
                "O-O", "O-O"
        ));

        /*
         * =========================================================
         * ENGLISH OPENING
         * ========================================================= */

        openings.add(Arrays.asList(
                "c4", "e5",
                "Nc3", "Nf6",
                "g3", "d5",
                "cxd5", "Nxd5",
                "Bg2", "Bc5"
        ));

        openings.add(Arrays.asList(
                "c4", "Nf6",
                "Nc3", "e6",
                "Nf3", "Bb4",
                "g3", "O-O",
                "Bg2", "d5"
        ));

        /*
         * =========================================================
         * RETI
         * ========================================================= */

        openings.add(Arrays.asList(
                "Nf3", "d5",
                "g3", "Nf6",
                "Bg2", "e6",
                "O-O", "Be7",
                "d4", "O-O"
        ));

        openings.add(Arrays.asList(
                "Nf3", "Nf6",
                "g3", "g6",
                "Bg2", "Bg7",
                "O-O", "O-O",
                "d4", "d5"
        ));

        /*
         * =========================================================
         * NIMZOWITSCH DEFENSE
         * ========================================================= */

        /*
         * Nimzowitsch Defense
         *
         * Included, but the line is intentionally short.
         * If the opponent deviates, the normal search takes over.
         */
        openings.add(Arrays.asList(
                "e4", "Nc6",
                "d4", "d5",
                "e5", "Bf5",
                "Nf3", "e6",
                "Bb5", "Qd7"
        ));

        /*
         * =========================================================
         * ADDITIONAL COMMON e4 LINES
         * ========================================================= */

        /*
         * Center Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "d4", "exd4",
                "Qxd4", "Nc6",
                "Qd1", "Nf6",
                "Nc3", "Bb4"
        ));

        /*
         * Bishop's Opening
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Bc4", "Nf6",
                "d3", "Bc5",
                "Nf3", "d6",
                "O-O", "O-O"
        ));
    }

    /**
     * Returns all opening lines.
     */
    public List<List<String>> getOpenings() {
        return openings;
    }
}