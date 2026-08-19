package chess.engine.opening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collection of opening lines for the chess engine.
 *
 * Moves are stored in SAN notation.
 *
 * Example:
 *
 * e4 e5 Nf3 Nc6 Bb5
 */
public class OpeningBook {

    private final List<List<String>> openings = new ArrayList<>();

    public OpeningBook() {

        /*
         * Italian Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bc4", "Bc5",
                "c3", "Nf6",
                "d3", "d6"
        ));

        /*
         * Ruy Lopez
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Bb5", "a6",
                "Ba4", "Nf6",
                "O-O", "Be7"
        ));

        /*
         * Scotch Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "d4", "exd4",
                "Nxd4"
        ));

        /*
         * Four Knights Game
         */
        openings.add(Arrays.asList(
                "e4", "e5",
                "Nf3", "Nc6",
                "Nc3", "Nf6",
                "Bb5"
        ));

        /*
         * Queen's Gambit
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "e6",
                "Nc3", "Nf6",
                "Bg5", "Be7"
        ));

        /*
         * Queen's Gambit Declined
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "e6",
                "Nc3", "Nf6",
                "Bg5", "Be7",
                "e3", "O-O"
        ));

        /*
         * Queen's Gambit Accepted
         */
        openings.add(Arrays.asList(
                "d4", "d5",
                "c4", "dxc4",
                "Nf3", "Nf6",
                "e3", "e6",
                "Bxc4"
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
         * Sicilian Defense
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "d6",
                "d4", "cxd4",
                "Nxd4", "Nf6",
                "Nc3"
        ));

        /*
         * Sicilian Accelerated Dragon
         */
        openings.add(Arrays.asList(
                "e4", "c5",
                "Nf3", "Nc6",
                "d4", "cxd4",
                "Nxd4", "g6",
                "Nc3"
        ));

        /*
         * French Defense
         */
        openings.add(Arrays.asList(
                "e4", "e6",
                "d4", "d5",
                "Nc3", "Nf6",
                "Bg5", "Bb4"
        ));

        /*
         * Caro Kann
         */
        openings.add(Arrays.asList(
                "e4", "c6",
                "d4", "d5",
                "Nc3", "dxe4",
                "Nxe4", "Bf5"
        ));

        /*
         * Scandinavian Defense
         */
        openings.add(Arrays.asList(
                "e4", "d5",
                "exd5", "Qxd5",
                "Nc3", "Qd8"
        ));

        /*
         * English Opening
         */
        openings.add(Arrays.asList(
                "c4", "e5",
                "Nc3", "Nf6",
                "g3", "d5",
                "cxd5", "Nxd5"
        ));

        /*
         * Réti Opening
         */
        openings.add(Arrays.asList(
                "Nf3", "d5",
                "g3", "Nf6",
                "Bg2", "e6",
                "O-O", "Be7"
        ));

        /*
         * Nimzo Indian Defense
         */
        openings.add(Arrays.asList(
                "d4", "Nf6",
                "c4", "e6",
                "Nc3", "Bb4",
                "e3", "O-O"
        ));
    }

    public List<List<String>> getOpenings() {
        return openings;
    }
}