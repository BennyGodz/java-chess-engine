package chess.engine.opening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Large chess opening book.
 *
 * The book contains established theoretical openings and many
 * variations, including some less common but sound lines.
 *
 * Moves are stored in SAN notation.
 *
 * The opening manager follows a line as long as the opponent's
 * moves remain inside the book. Once the position leaves the book,
 * normal engine search takes over.
 */
public class OpeningBook {

    private final List<List<String>> openings = new ArrayList<>();

    public OpeningBook() {

        /*
         * =========================================================
         * 1. E4 OPENINGS
         * =========================================================
         */

        /*
         * Italian Game
         */
        add(
                "e4 e5 Nf3 Nc6 Bc4 Bc5",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d3 d6",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d4 exd4 cxd4",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 O-O Nf6 d3 O-O",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 O-O d6 d3 Nf6",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d4 exd4 cxd4 Bb4+",
                "e4 e5 Nf3 Nc6 Bc4 Bc5 b4 Bb6 a4 a6"
        );

        /*
         * Italian Two Knights
         */
        add(
                "e4 e5 Nf3 Nc6 Bc4 Nf6",
                "e4 e5 Nf3 Nc6 Bc4 Nf6 Ng5 d5 exd5 Na5",
                "e4 e5 Nf3 Nc6 Bc4 Nf6 d3 Bc5 O-O O-O",
                "e4 e5 Nf3 Nc6 Bc4 Nf6 d4 exd4 O-O",
                "e4 e5 Nf3 Nc6 Bc4 Nf6 Nc3 Bc5 d3 O-O"
        );

        /*
         * Ruy Lopez
         */
        add(
                "e4 e5 Nf3 Nc6 Bb5",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Nxe4",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 Re1 b5 Bb3",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 d6",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 O-O c3 d6",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O"
        );

        /*
         * Ruy Lopez Berlin
         */
        add(
                "e4 e5 Nf3 Nc6 Bb5 Nf6",
                "e4 e5 Nf3 Nc6 Bb5 Nf6 O-O Nxe4 Re1 Nd6",
                "e4 e5 Nf3 Nc6 Bb5 Nf6 O-O Nxe4 Re1 Nd6 Nxe5 Be7",
                "e4 e5 Nf3 Nc6 Bb5 Nf6 d3 Bc5 O-O O-O"
        );

        /*
         * Ruy Lopez Marshall
         */
        add(
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O c3 d5",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O c3 d5 exd5 Nxd5",
                "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O c3 d5 exd5 Nxd5 d4"
        );

        /*
         * Scotch
         */
        add(
                "e4 e5 Nf3 Nc6 d4",
                "e4 e5 Nf3 Nc6 d4 exd4 Nxd4",
                "e4 e5 Nf3 Nc6 d4 exd4 Bc4",
                "e4 e5 Nf3 Nc6 d4 exd4 Nxd4 Nf6 Nc3 Bb4",
                "e4 e5 Nf3 Nc6 d4 exd4 Nxd4 Bc5 Be3 Qf6",
                "e4 e5 Nf3 Nc6 d4 exd4 Nxd4 Qh4"
        );

        /*
         * Four Knights
         */
        add(
                "e4 e5 Nf3 Nc6 Nc3 Nf6",
                "e4 e5 Nf3 Nc6 Nc3 Nf6 Bb5 Bb4 O-O O-O",
                "e4 e5 Nf3 Nc6 Nc3 Nf6 Bb5 Bb4 O-O O-O Re1 d6",
                "e4 e5 Nf3 Nc6 Nc3 Nf6 d4 exd4 Nxd4"
        );

        /*
         * King's Gambit
         *
         * Included because it is historically important and
         * theoretically playable, but not favored by the engine.
         */
        add(
                "e4 e5 f4",
                "e4 e5 f4 exf4 Nf3 d5",
                "e4 e5 f4 exf4 Nf3 Nf6",
                "e4 e5 f4 exf4 Bc4 Nf6 Nf3",
                "e4 e5 f4 Bc5 Nf3 d6"
        );

        /*
         * Caro Kann
         */
        add(
                "e4 c6 d4 d5",
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5",
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Nd7",
                "e4 c6 d4 d5 e5 Bf5",
                "e4 c6 d4 d5 e5 Bf5 Nc3 e6",
                "e4 c6 d4 d5 Nd2 dxe4 Nxe4 Bf5",
                "e4 c6 d4 d5 exd5 cxd5"
        );

        /*
         * Caro Kann Classical
         */
        add(
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5",
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5 Ng3 Bg6 h4 h6",
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5 Ng3 Bg6 Bc4",
                "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5 f3"
        );

        /*
         * French Defense
         */
        add(
                "e4 e6 d4 d5",
                "e4 e6 d4 d5 Nc3 Nf6",
                "e4 e6 d4 d5 Nc3 Bb4",
                "e4 e6 d4 d5 e5",
                "e4 e6 d4 d5 e5 c5 Nc3 Nc6",
                "e4 e6 d4 d5 Nd2 Nf6",
                "e4 e6 d4 d5 exd5 exd5"
        );

        /*
         * French Winawer
         */
        add(
                "e4 e6 d4 d5 Nc3 Bb4",
                "e4 e6 d4 d5 Nc3 Bb4 e5 c5 a3 Bxc3+ bxc3",
                "e4 e6 d4 d5 Nc3 Bb4 e5 c5 Qg4 Ne7",
                "e4 e6 d4 d5 Nc3 Bb4 e5 c5 Bd2 Ne7"
        );

        /*
         * French Classical
         */
        add(
                "e4 e6 d4 d5 Nc3 Nf6",
                "e4 e6 d4 d5 Nc3 Nf6 e5 Nfd7",
                "e4 e6 d4 d5 Nc3 Nf6 Bg5 Be7",
                "e4 e6 d4 d5 Nc3 Nf6 e5 Nfd7 f4 c5"
        );

        /*
         * Sicilian
         */
        add(
                "e4 c5",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4",
                "e4 c5 Nc3 Nc6 Nf3",
                "e4 c5 Nf3 d6 Nc3"
        );

        /*
         * Sicilian Najdorf
         */
        add(
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Bg5 e6",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Be3 e5",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 f3 e5",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Bc4 e6"
        );

        /*
         * Sicilian Dragon
         */
        add(
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6 Be3 Bg7",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6 Bc4 Bg7",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6 f3 Bg7"
        );

        /*
         * Accelerated Dragon
         */
        add(
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 g6",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 g6 Nc3 Bg7",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 g6 Nxc6 bxc6",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 g6 Bc4 Bg7"
        );

        /*
         * Sicilian Classical
         */
        add(
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Nc6",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Nc6 Bg5",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Be6"
        );

        /*
         * Sicilian Sveshnikov
         */
        add(
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 Nf6 Nc3 e5",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 Nf6 Nc3 e5 Ndb5 d6",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 Nf6 Nc3 e5 Nxc6 dxc6",
                "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 Nf6 Nc3 e5 Nb3"
        );

        /*
         * Sicilian Kan
         */
        add(
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6 Bd3 Bc5",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6 Nc3 Qc7",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6 Be2 Nf6"
        );

        /*
         * Sicilian Taimanov
         */
        add(
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6 Nc3 Qc7",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6 Be2 Nf6",
                "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6 Nxc6 bxc6"
        );

        /*
         * Sicilian Scheveningen
         */
        add(
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 e6",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 e6 Be3 Be7",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 e6 g4",
                "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 e6 Be2"
        );

        /*
         * Pirc
         */
        add(
                "e4 d6 d4 Nf6 Nc3 g6",
                "e4 d6 d4 Nf6 Nc3 g6 f4 Bg7",
                "e4 d6 d4 Nf6 Nc3 g6 Nf3 Bg7",
                "e4 d6 d4 Nf6 Nc3 g6 Be3 Bg7"
        );

        /*
         * Modern Defense
         */
        add(
                "e4 g6 d4 Bg7 Nc3",
                "e4 g6 d4 Bg7 Nc3 d6",
                "e4 g6 d4 Bg7 Nc3 c6",
                "e4 g6 d4 Bg7 Nc3 d6 f4"
        );

        /*
         * Scandinavian
         */
        add(
                "e4 d5 exd5 Qxd5",
                "e4 d5 exd5 Qxd5 Nc3 Qd8",
                "e4 d5 exd5 Qxd5 Nc3 Qa5",
                "e4 d5 exd5 Qxd5 Nc3 Qd6",
                "e4 d5 exd5 Nf6"
        );

        /*
         * Alekhine Defense
         */
        add(
                "e4 Nf6 e5 Nd5 d4 d6",
                "e4 Nf6 e5 Nd5 d4 d6 c4 Nb6",
                "e4 Nf6 e5 Nd5 d4 d6 Nc3",
                "e4 Nf6 e5 Nd5 d4 d6 Nf3"
        );

        /*
         * Nimzowitsch Defense
         */
        add(
                "e4 Nc6",
                "e4 Nc6 d4 d5",
                "e4 Nc6 Nf3 d5",
                "e4 Nc6 d4 e5",
                "e4 Nc6 Nf3 e5"
        );

        /*
         * Owen Defense
         */
        add(
                "e4 b6",
                "e4 b6 d4 Bb7 Nc3 e6",
                "e4 b6 d4 Bb7 Bd3 e6",
                "e4 b6 d4 Bb7 Nc3 e6 Nf3"
        );


        /*
         * =========================================================
         * 2. D4 OPENINGS
         * =========================================================
         */

        /*
         * Queen's Gambit
         */
        add(
                "d4 d5 c4",
                "d4 d5 c4 e6 Nc3 Nf6 Bg5 Be7",
                "d4 d5 c4 e6 Nc3 Nf6 Nf3",
                "d4 d5 c4 e6 Nc3 Nf6 cxd5",
                "d4 d5 c4 dxc4 Nf3 Nf6 e3 e6",
                "d4 d5 c4 dxc4 Nf3 Nf6 e3 e6 Bxc4"
        );

        /*
         * Queen's Gambit Declined
         */
        add(
                "d4 d5 c4 e6",
                "d4 d5 c4 e6 Nc3 Nf6 Bg5 Be7",
                "d4 d5 c4 e6 Nc3 Nf6 Nf3 Be7",
                "d4 d5 c4 e6 Nc3 Nf6 Bg5 Nbd7",
                "d4 d5 c4 e6 Nf3 Nf6 Nc3 Be7",
                "d4 d5 c4 e6 Nf3 Nf6 g3 Be7"
        );

        /*
         * QGD Orthodox
         */
        add(
                "d4 d5 c4 e6 Nc3 Nf6 Nf3 Be7",
                "d4 d5 c4 e6 Nc3 Nf6 Nf3 Be7 Bg5 O-O",
                "d4 d5 c4 e6 Nc3 Nf6 Nf3 Be7 e3 O-O",
                "d4 d5 c4 e6 Nc3 Nf6 Nf3 Be7 Bf4 O-O"
        );

        /*
         * Slav
         */
        add(
                "d4 d5 c4 c6",
                "d4 d5 c4 c6 Nf3 Nf6 Nc3 dxc4",
                "d4 d5 c4 c6 Nc3 Nf6 Nf3 dxc4",
                "d4 d5 c4 c6 Nf3 Nf6 e3 Bf5",
                "d4 d5 c4 c6 Nc3 Nf6 e3 Bf5"
        );

        /*
         * Semi Slav
         */
        add(
                "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6",
                "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6 e3 Nbd7",
                "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6 Bg5 dxc4",
                "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6 Qc2"
        );

        /*
         * Catalan
         */
        add(
                "d4 Nf6 c4 e6 g3",
                "d4 Nf6 c4 e6 g3 d5 Bg2 Be7",
                "d4 Nf6 c4 e6 g3 d5 Bg2 Be7 Nf3 O-O",
                "d4 Nf6 c4 e6 g3 d5 Bg2 dxc4",
                "d4 Nf6 c4 e6 g3 d5 Bg2 Bb4+"
        );

        /*
         * King's Indian
         */
        add(
                "d4 Nf6 c4 g6 Nc3 Bg7",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 f3 O-O",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Be2 O-O",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 h3 O-O"
        );

        /*
         * King's Indian Classical
         */
        add(
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O Be2 e5",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O Be2 e5 d5",
                "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O Be2 e5 O-O"
        );

        /*
         * Nimzo Indian
         */
        add(
                "d4 Nf6 c4 e6 Nc3 Bb4",
                "d4 Nf6 c4 e6 Nc3 Bb4 e3 O-O",
                "d4 Nf6 c4 e6 Nc3 Bb4 Qc2 O-O",
                "d4 Nf6 c4 e6 Nc3 Bb4 Bg5 h6",
                "d4 Nf6 c4 e6 Nc3 Bb4 Nf3 O-O"
        );

        /*
         * Queen's Indian
         */
        add(
                "d4 Nf6 c4 e6 Nf3 b6",
                "d4 Nf6 c4 e6 Nf3 b6 g3 Bb7",
                "d4 Nf6 c4 e6 Nf3 b6 a3 Ba6",
                "d4 Nf6 c4 e6 Nf3 b6 Nc3 Bb7"
        );

        /*
         * Bogo Indian
         */
        add(
                "d4 Nf6 c4 e6 Nf3 Bb4+",
                "d4 Nf6 c4 e6 Nf3 Bb4+ Bd2",
                "d4 Nf6 c4 e6 Nf3 Bb4+ Nbd2",
                "d4 Nf6 c4 e6 Nf3 Bb4+ Bd2 Qe7"
        );

        /*
         * Grunfeld
         */
        add(
                "d4 Nf6 c4 g6 Nc3 d5",
                "d4 Nf6 c4 g6 Nc3 d5 cxd5 Nxd5",
                "d4 Nf6 c4 g6 Nc3 d5 Nf3 Bg7",
                "d4 Nf6 c4 g6 Nc3 d5 Bg5 Ne4"
        );

        /*
         * Benoni
         */
        add(
                "d4 Nf6 c4 c5 d5",
                "d4 Nf6 c4 c5 d5 e6 Nc3 exd5 cxd5 d6",
                "d4 Nf6 c4 c5 d5 e6 Nc3 exd5 cxd5 d6 Nf3",
                "d4 Nf6 c4 c5 d5 e6 Nc3 exd5 cxd5 d6 e4"
        );

        /*
         * Benko Gambit
         */
        add(
                "d4 Nf6 c4 c5 d5 b5",
                "d4 Nf6 c4 c5 d5 b5 cxb5 a6",
                "d4 Nf6 c4 c5 d5 b5 cxb5 a6 b6",
                "d4 Nf6 c4 c5 d5 b5 cxb5 a6 Nc3"
        );

        /*
         * Dutch Defense
         */
        add(
                "d4 f5",
                "d4 f5 c4 Nf6 Nc3 e6",
                "d4 f5 c4 Nf6 g3 e6 Bg2",
                "d4 f5 c4 Nf6 Nc3 g6",
                "d4 f5 g3 Nf6 Bg2 e6"
        );

        /*
         * Dutch Stonewall
         */
        add(
                "d4 f5 c4 Nf6 Nc3 e6 Nf3 d5",
                "d4 f5 c4 Nf6 Nc3 e6 Nf3 d5 g3 c6",
                "d4 f5 c4 Nf6 Nc3 e6 Nf3 d5 Bg5",
                "d4 f5 c4 Nf6 Nc3 e6 Nf3 d5 e3"
        );


        /*
         * =========================================================
         * 3. ENGLISH OPENING
         * =========================================================
         */

        /*
         * English Symmetrical
         */
        add(
                "c4 c5",
                "c4 c5 Nc3 Nc6 Nf3 Nf6",
                "c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7",
                "c4 c5 Nf3 Nc6 g3 g6 Bg2 Bg7",
                "c4 c5 Nc3 Nf6 g3 g6 Bg2 Bg7"
        );

        /*
         * English vs e5
         */
        add(
                "c4 e5",
                "c4 e5 Nc3 Nf6 Nf3 Nc6 g3",
                "c4 e5 Nc3 Nf6 g3 d5",
                "c4 e5 Nc3 Nf6 Nf3 Nc6 g3 d5"
        );

        /*
         * English Four Knights
         */
        add(
                "c4 e5 Nc3 Nf6 Nf3 Nc6",
                "c4 e5 Nc3 Nf6 Nf3 Nc6 g3 Bb4",
                "c4 e5 Nc3 Nf6 Nf3 Nc6 g3 d5",
                "c4 e5 Nc3 Nf6 Nf3 Nc6 e3 Bb4"
        );


        /*
         * =========================================================
         * 4. RETI / NIMZO LARSEN / OTHER SOUND SYSTEMS
         * =========================================================
         */

        /*
         * Reti
         */
        add(
                "Nf3 d5 g3 Nf6 Bg2",
                "Nf3 d5 g3 Nf6 Bg2 e6 O-O Be7",
                "Nf3 d5 g3 Nf6 Bg2 c5 O-O Nc6",
                "Nf3 d5 g3 Nf6 Bg2 Bf5 O-O e6"
        );

        /*
         * Reti King's Indian Attack
         */
        add(
                "Nf3 Nf6 g3 g6 Bg2 Bg7 O-O",
                "Nf3 Nf6 g3 g6 Bg2 Bg7 O-O O-O d3",
                "Nf3 Nf6 g3 g6 Bg2 Bg7 O-O d6 d3",
                "Nf3 Nf6 g3 g6 Bg2 Bg7 O-O c5"
        );

        /*
         * Nimzo Larsen
         */
        add(
                "b3 d5 Bb2 Nf6 e3",
                "b3 d5 Bb2 Nf6 e3 e6 Nf3 Be7",
                "b3 d5 Bb2 Nf6 e3 c5 Nf3",
                "b3 d5 Bb2 Nf6 Nf3 e6 e3"
        );

        /*
         * Colle
         */
        add(
                "d4 d5 Nf3 Nf6 e3",
                "d4 d5 Nf3 Nf6 e3 e6 Bd3 Be7",
                "d4 d5 Nf3 Nf6 e3 e6 Bd3 c5 O-O",
                "d4 d5 Nf3 Nf6 e3 e6 Bd3 c5 O-O Nc6"
        );

        /*
         * London System
         */
        add(
                "d4 d5 Nf3 Nf6 Bf4",
                "d4 d5 Nf3 Nf6 Bf4 e6 e3 Bd6",
                "d4 d5 Nf3 Nf6 Bf4 e6 e3 Bd6 Bd3",
                "d4 d5 Nf3 Nf6 Bf4 c5 e3 Nc6",
                "d4 Nf6 Nf3 d5 Bf4 e6 e3"
        );

        /*
         * Trompowsky
         */
        add(
                "d4 Nf6 Bg5",
                "d4 Nf6 Bg5 Ne4 Bh4 d5",
                "d4 Nf6 Bg5 d5 e3",
                "d4 Nf6 Bg5 e6 e4"
        );


        /*
         * =========================================================
         * 5. LESS COMMON BUT SOUND OPENINGS
         * =========================================================
         */

        /*
         * Veresov
         */
        add(
                "d4 d5 Nc3 Nf6 Bg5",
                "d4 d5 Nc3 Nf6 Bg5 Bf5",
                "d4 d5 Nc3 Nf6 Bg5 e6 e4",
                "d4 d5 Nc3 Nf6 Bg5 c6"
        );

        /*
         * Torre Attack
         */
        add(
                "d4 Nf6 Nf3 e6 Bg5",
                "d4 Nf6 Nf3 e6 Bg5 d5 e3 Be7",
                "d4 Nf6 Nf3 e6 Bg5 b6 e3 Bb7",
                "d4 Nf6 Nf3 e6 Bg5 c5"
        );

        /*
         * Barry Attack
         */
        add(
                "d4 Nf6 Nf3 g6 Nc3 d5 Bf4",
                "d4 Nf6 Nf3 g6 Nc3 d5 Bf4 Bg7 e3",
                "d4 Nf6 Nf3 g6 Nc3 d5 Bf4 c6 e3"
        );

        /*
         * Blackmar style positions are intentionally NOT included.
         * The book favors sound long term positions.
         */

        /*
         * King's Indian Attack
         */
        add(
                "Nf3 Nf6 g3 e6 Bg2 d5 O-O",
                "Nf3 Nf6 g3 e6 Bg2 d5 O-O Be7",
                "Nf3 Nf6 g3 e6 Bg2 d5 d3 Be7 O-O",
                "Nf3 Nf6 g3 e6 Bg2 d5 d3 c5 O-O"
        );

        /*
         * Pirc Austrian
         */
        add(
                "e4 d6 d4 Nf6 Nc3 g6 f4",
                "e4 d6 d4 Nf6 Nc3 g6 f4 Bg7 Nf3 O-O",
                "e4 d6 d4 Nf6 Nc3 g6 f4 Bg7 e5",
                "e4 d6 d4 Nf6 Nc3 g6 f4 Bg7 Be2"
        );

        /*
         * Modern Classical
         */
        add(
                "e4 g6 d4 Bg7 Nc3 d6 Nf3",
                "e4 g6 d4 Bg7 Nc3 d6 Nf3 Nf6",
                "e4 g6 d4 Bg7 Nc3 d6 Be3",
                "e4 g6 d4 Bg7 Nc3 d6 f4"
        );
    }

    /**
     * Add several opening lines at once.
     */
    private void add(String... lines) {

        for (String line : lines) {

            if (line == null || line.isBlank()) {
                continue;
            }

            openings.add(
                    Arrays.asList(
                            line.trim().split("\\s+")
                    )
            );
        }
    }

    /**
     * Return every opening line in the book.
     */
    public List<List<String>> getOpenings() {
        return openings;
    }
}