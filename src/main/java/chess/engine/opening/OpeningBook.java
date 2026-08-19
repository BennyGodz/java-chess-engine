package chess.engine.opening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Large chess opening book.
 *
 * The book contains:
 *
 * Strong mainline openings
 * Common theoretical variations
 * Sidelines
 * Gambits
 * Tactical opening traps
 * Refutations of dubious openings
 *
 * The engine does NOT randomly invent opening moves.
 * Every move comes from a known theoretical line.
 *
 * Moves are stored in SAN notation.
 */
public class OpeningBook {

    private final List<List<String>> openings = new ArrayList<>();

    public OpeningBook() {

        /*
         * =========================================================
         * KING'S PAWN OPENINGS
         * =========================================================
         */

        /*
         * Italian Game
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "c3 Nf6",
                "d3 d6",
                "O-O O-O",
                "Re1 a6",
                "Bb3 Ba7"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "c3 Nf6",
                "d4 exd4",
                "cxd4 Bb4+",
                "Nc3"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "O-O Nf6",
                "d3 O-O",
                "Nc3 d6",
                "Bg5"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "d3 Nf6",
                "O-O O-O",
                "c3 d6",
                "Re1"
        );

        /*
         * Italian Two Knights
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Nf6",
                "Ng5 d5",
                "exd5 Na5",
                "Bb5+ c6",
                "dxc6 bxc6",
                "Be2"
        );

        /*
         * Fried Liver
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Nf6",
                "Ng5 d5",
                "exd5 Nxd5",
                "Nxf7 Kxf7",
                "Qf3+"
        );

        /*
         * Fried Liver main defensive line
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Nf6",
                "Ng5 d5",
                "exd5 Na5",
                "Bb5+ c6",
                "dxc6 bxc6",
                "Be2"
        );

        /*
         * Italian Evans Gambit
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "b4 Bxb4",
                "c3 Ba5",
                "d4 exd4",
                "O-O"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "b4 Bxb4",
                "c3 Ba5",
                "d4 exd4",
                "Qb3"
        );

        /*
         * Ruy Lopez
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "O-O Be7",
                "Re1 b5",
                "Bb3 d6",
                "c3 O-O",
                "h3"
        );

        /*
         * Ruy Lopez Berlin
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 Nf6",
                "O-O Nxe4",
                "Re1 Nd6",
                "Nxe5 Be7",
                "Bxc6 dxc6",
                "d4"
        );

        /*
         * Ruy Lopez Marshall
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "O-O Be7",
                "Re1 b5",
                "Bb3 O-O",
                "c3 d5",
                "exd5 Nxd5",
                "Nxe5 Nxe5",
                "Rxe5 c6"
        );

        /*
         * Ruy Lopez Closed
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "O-O Be7",
                "Re1 b5",
                "Bb3 d6",
                "c3 O-O",
                "h3 Nb8",
                "d4"
        );

        /*
         * Scotch Game
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "d4 exd4",
                "Nxd4 Nf6",
                "Nxc6 bxc6",
                "e5 Qe7",
                "Qe2"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "d4 exd4",
                "Nxd4 Bc5",
                "Be3 Qf6",
                "c3 Nge7",
                "Bb5"
        );

        /*
         * Four Knights
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Nc3 Nf6",
                "Bb5 Bb4",
                "O-O O-O",
                "d3 d6"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Nc3 Nf6",
                "Bb5 Bb4",
                "O-O O-O",
                "d4 exd4",
                "Nxd4"
        );

        /*
         * King's Gambit
         */
        add(
                "e4 e5",
                "f4 exf4",
                "Nf3 d5",
                "exd5 Nf6",
                "Bb5+ c6",
                "dxc6 bxc6"
        );

        /*
         * King's Gambit Falkbeer
         */
        add(
                "e4 e5",
                "f4 d5",
                "exd5 e4",
                "d3 Nf6",
                "Nc3 Bb4"
        );

        /*
         * Danish Gambit
         */
        add(
                "e4 e5",
                "d4 exd4",
                "c3 dxc3",
                "Bc4 cxb2",
                "Bxb2"
        );

        /*
         * Center Game
         */
        add(
                "e4 e5",
                "d4 exd4",
                "Qxd4 Nc6",
                "Qe3 Nf6",
                "Nc3 Bb4"
        );

        /*
         * Ponziani
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "c3 Nf6",
                "d4 exd4",
                "e5 Nd5",
                "cxd4 d6"
        );

        /*
         * Vienna
         */
        add(
                "e4 e5",
                "Nc3 Nf6",
                "f4 d5",
                "fxe5 Nxe4",
                "Nf3"
        );

        add(
                "e4 e5",
                "Nc3 Nf6",
                "g3 d5",
                "exd5 Nxd5",
                "Bg2"
        );

        /*
         * Portuguese Gambit
         */
        add(
                "e4 d5",
                "exd5 Nf6",
                "d4 Bg4",
                "f3 Bf5",
                "Bb5+"
        );

        /*
         * Scandinavian
         */
        add(
                "e4 d5",
                "exd5 Qxd5",
                "Nc3 Qd8",
                "d4 Nf6",
                "Nf3"
        );

        add(
                "e4 d5",
                "exd5 Nf6",
                "d4 Nxd5",
                "Nf3"
        );

        /*
         * Caro Kann
         */
        add(
                "e4 c6",
                "d4 d5",
                "Nc3 dxe4",
                "Nxe4 Bf5",
                "Ng3 Bg6",
                "Nf3 Nd7",
                "h4 h6"
        );

        add(
                "e4 c6",
                "d4 d5",
                "Nc3 dxe4",
                "Nxe4 Bf5",
                "Ng3 Bg6",
                "Nf3 Nd7",
                "Bc4"
        );

        /*
         * Caro Kann Classical
         */
        add(
                "e4 c6",
                "d4 d5",
                "Nc3 dxe4",
                "Nxe4 Bf5",
                "Ng3 Bg6",
                "Nf3 Nd7",
                "Bc4 e6",
                "O-O"
        );

        /*
         * Caro Kann Advance
         */
        add(
                "e4 c6",
                "d4 d5",
                "e5 Bf5",
                "Nc3 e6",
                "g4 Bg6",
                "Nge2"
        );

        /*
         * Caro Kann Two Knights
         */
        add(
                "e4 c6",
                "Nc3 d5",
                "Nf3 Bg4",
                "h3 Bh5",
                "d4"
        );

        /*
         * French Defense
         */
        add(
                "e4 e6",
                "d4 d5",
                "Nc3 Nf6",
                "Bg5 Bb4",
                "e5 h6",
                "Bh4 g5",
                "Bg3 Ne4"
        );

        /*
         * French Tarrasch
         */
        add(
                "e4 e6",
                "d4 d5",
                "Nd2 Nf6",
                "e5 Nfd7",
                "Bd3 c5",
                "c3 Nc6"
        );

        /*
         * French Advance
         */
        add(
                "e4 e6",
                "d4 d5",
                "e5 c5",
                "c3 Nc6",
                "Nf3 Qb6",
                "Bd3 cxd4",
                "cxd4 Bd7"
        );

        /*
         * Sicilian Najdorf
         */
        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 a6",
                "Be3 e5",
                "Nb3 Be6"
        );

        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 a6",
                "Bg5 e6",
                "f4 Be7",
                "Qf3 Qc7"
        );

        /*
         * Sicilian Dragon
         */
        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 g6",
                "Be3 Bg7",
                "f3 O-O",
                "Qd2 Nc6"
        );

        /*
         * Accelerated Dragon
         */
        add(
                "e4 c5",
                "Nf3 Nc6",
                "d4 cxd4",
                "Nxd4 g6",
                "Nc3 Bg7",
                "Be3 Nf6",
                "Bc4 O-O"
        );

        /*
         * Sicilian Classical
         */
        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 Nc6",
                "Bg5 e6",
                "Qd2 Be7"
        );

        /*
         * Sicilian Scheveningen
         */
        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 e6",
                "Be3 Be7",
                "Qd2 O-O"
        );

        /*
         * Sicilian Sveshnikov
         */
        add(
                "e4 c5",
                "Nf3 Nc6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 e5",
                "Ndb5 d6",
                "Bg5 a6",
                "Na3 b5"
        );

        /*
         * Sicilian Kalashnikov
         */
        add(
                "e4 c5",
                "Nf3 Nc6",
                "d4 cxd4",
                "Nxd4 e5",
                "Nb5 d6",
                "c4 Nf6"
        );

        /*
         * Sicilian Classical variation
         */
        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 Nc6",
                "Be2 e6",
                "O-O Be7"
        );

        /*
         * Alapin
         */
        add(
                "e4 c5",
                "c3 Nf6",
                "e5 Nd5",
                "d4 cxd4",
                "Nf3 Nc6"
        );

        /*
         * Smith Morra Gambit
         */
        add(
                "e4 c5",
                "d4 cxd4",
                "c3 dxc3",
                "Nxc3 Nc6",
                "Nf3 d6",
                "Bc4"
        );

        /*
         * Closed Sicilian
         */
        add(
                "e4 c5",
                "Nc3 Nc6",
                "g3 g6",
                "Bg2 Bg7",
                "d3 d6",
                "Nge2"
        );

        /*
         * =========================================================
         * QUEEN'S PAWN OPENINGS
         * =========================================================
         */

        /*
         * Queen's Gambit Declined
         */
        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Be7",
                "e3 O-O",
                "Nf3 Nbd7"
        );

        /*
         * QGD Exchange
         */
        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Be7",
                "cxd5 exd5",
                "e3 O-O"
        );

        /*
         * QGD Orthodox
         */
        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Be7",
                "e3 O-O",
                "Nf3 Nbd7",
                "Bd3 dxc4",
                "Bxc4"
        );

        /*
         * Queen's Gambit Accepted
         */
        add(
                "d4 d5",
                "c4 dxc4",
                "Nf3 Nf6",
                "e3 e6",
                "Bxc4 c5",
                "O-O a6"
        );

        /*
         * Slav
         */
        add(
                "d4 d5",
                "c4 c6",
                "Nf3 Nf6",
                "Nc3 dxc4",
                "a4 Bf5",
                "e3 e6"
        );

        /*
         * Semi Slav
         */
        add(
                "d4 d5",
                "c4 c6",
                "Nf3 Nf6",
                "Nc3 e6",
                "e3 Nbd7",
                "Bd3 dxc4",
                "Bxc4"
        );

        /*
         * Catalan
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "g3 d5",
                "Bg2 Be7",
                "Nf3 O-O",
                "O-O"
        );

        /*
         * King's Indian
         */
        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6",
                "Nf3 O-O",
                "Be2 e5",
                "O-O"
        );

        /*
         * King's Indian Classical
         */
        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6",
                "Nf3 O-O",
                "Be2 e5",
                "O-O Nc6",
                "d5 Ne7"
        );

        /*
         * King's Indian Sämisch
         */
        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6",
                "f3 O-O",
                "Be3 e5",
                "d5 c6"
        );

        /*
         * Nimzo Indian
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "e3 O-O",
                "Bd3 d5",
                "Nf3 c5",
                "O-O"
        );

        /*
         * Nimzo Rubinstein
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "e3 O-O",
                "Bd3 d5",
                "Nf3 c5",
                "O-O Nc6"
        );

        /*
         * Nimzo Classical
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "Bg5 h6",
                "Bh4 c5",
                "d5 d6"
        );

        /*
         * Bogo Indian
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nf3 Bb4+",
                "Bd2 Qe7",
                "g3 Nc6",
                "Bg2 O-O"
        );

        /*
         * Queen's Indian
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nf3 b6",
                "g3 Ba6",
                "Qa4 Bb7",
                "Bg2 Be7",
                "O-O O-O"
        );

        /*
         * Grünfeld
         */
        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 d5",
                "cxd5 Nxd5",
                "e4 Nxc3",
                "bxc3 Bg7",
                "Be3 O-O"
        );

        /*
         * Grünfeld Exchange
         */
        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 d5",
                "cxd5 Nxd5",
                "e4 Nxc3",
                "bxc3 Bg7",
                "Be3 c5"
        );

        /*
         * Budapest Gambit
         */
        add(
                "d4 Nf6",
                "c4 e5",
                "dxe5 Ng4",
                "Bf4 Nc6",
                "Nf3 Bb4+",
                "Nbd2 Qe7"
        );

        /*
         * Albin Counter Gambit
         */
        add(
                "d4 d5",
                "c4 e5",
                "dxe5 d4",
                "Nf3 Nc6",
                "a3 Be6"
        );

        /*
         * Englund Gambit
         *
         * Included so the engine knows how to respond.
         * It is NOT used as a preferred repertoire move.
         */
        add(
                "d4 e5",
                "dxe5 Nc6",
                "Nf3 Qe7",
                "Bf4 Qb4+",
                "Bd2 Qxb2",
                "Bc3"
        );

        /*
         * ICBM style Englund trap
         */
        add(
                "d4 e5",
                "dxe5 Nc6",
                "Nf3 d6",
                "exd6 Bxd6",
                "Nc3"
        );

        /*
         * Another Englund response
         */
        add(
                "d4 e5",
                "dxe5 Nc6",
                "Nf3 Qe7",
                "Bf4"
        );

        /*
         * Budapest Adler
         */
        add(
                "d4 Nf6",
                "c4 e5",
                "dxe5 Ng4",
                "Nf3 Nc6",
                "Bf4 Bb4+",
                "Nbd2 Qe7"
        );

        /*
         * =========================================================
         * ENGLISH OPENING
         * =========================================================
         */

        add(
                "c4 e5",
                "Nc3 Nf6",
                "g3 Bb4",
                "Bg2 O-O",
                "e4 c6",
                "Nge2 d5"
        );

        add(
                "c4 e5",
                "Nc3 Nf6",
                "g3 d5",
                "cxd5 Nxd5",
                "Bg2 Nc6",
                "Nf3"
        );

        add(
                "c4 Nf6",
                "Nc3 e6",
                "Nf3 d5",
                "d4 Be7",
                "g3 O-O",
                "Bg2"
        );

        add(
                "c4 c5",
                "Nc3 Nc6",
                "g3 g6",
                "Bg2 Bg7",
                "Nf3 Nf6",
                "O-O O-O"
        );

        /*
         * English Symmetrical
         */
        add(
                "c4 c5",
                "Nc3 Nc6",
                "g3 g6",
                "Bg2 Bg7",
                "Nf3 Nf6",
                "O-O O-O",
                "d3 d6"
        );

        /*
         * =========================================================
         * RÉTI
         * =========================================================
         */

        add(
                "Nf3 d5",
                "g3 Nf6",
                "Bg2 e6",
                "O-O Be7",
                "d4 O-O",
                "c4"
        );

        add(
                "Nf3 Nf6",
                "g3 g6",
                "Bg2 Bg7",
                "O-O O-O",
                "c4 d6",
                "d4"
        );

        /*
         * =========================================================
         * NIMZOWITSCH DEFENSE
         *
         * Included because opponents may play it.
         * The engine should know the theoretical responses.
         * It is NOT preferred when choosing Black's first move.
         * =========================================================
         */

        add(
                "e4 Nc6",
                "d4 d5",
                "e5 Ne4",
                "Bd3 Bf5",
                "Nf3 e6",
                "O-O"
        );

        add(
                "e4 Nc6",
                "Nf3 d5",
                "exd5 Qxd5",
                "Nc3"
        );

        add(
                "e4 Nc6",
                "d4 d5",
                "Nc3 dxe4",
                "d5 Nb8"
        );

        /*
         * =========================================================
         * PIRC / MODERN
         * =========================================================
         */

        add(
                "e4 d6",
                "d4 Nf6",
                "Nc3 g6",
                "f4 Bg7",
                "Nf3 O-O",
                "Be2"
        );

        add(
                "e4 g6",
                "d4 Bg7",
                "Nc3 d6",
                "Nf3 Nf6",
                "Be2 O-O",
                "O-O"
        );

        /*
         * =========================================================
         * DUTCH DEFENSE
         * =========================================================
         */

        add(
                "d4 f5",
                "c4 Nf6",
                "Nc3 e6",
                "Nf3 d5",
                "g3 Be7",
                "Bg2 O-O"
        );

        add(
                "d4 f5",
                "g3 Nf6",
                "Bg2 e6",
                "Nf3 Be7",
                "O-O O-O",
                "c4"
        );

        /*
         * =========================================================
         * QUEEN'S INDIAN / BENONI
         * =========================================================
         */

        add(
                "d4 Nf6",
                "c4 e6",
                "Nf3 c5",
                "d5 exd5",
                "cxd5 d6",
                "Nc3 g6"
        );

        /*
         * Modern Benoni
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nf3 c5",
                "d5 exd5",
                "cxd5 d6",
                "Nc3 g6",
                "Nd2"
        );

        /*
         * Benoni Classical
         */
        add(
                "d4 Nf6",
                "c4 e6",
                "Nf3 c5",
                "d5 exd5",
                "cxd5 d6",
                "Nc3 g6",
                "Nd2 Bg7",
                "e4"
        );

        /*
         * Benko Gambit
         */
        add(
                "d4 Nf6",
                "c4 c5",
                "d5 b5",
                "cxb5 a6",
                "bxa6 Bxa6",
                "Nc3 d6"
        );

        /*
         * =========================================================
         * COMMON TRAPS AND DUBIOUS OPENINGS
         *
         * These lines are here so the engine recognizes the
         * position and knows the theoretical response.
         *
         * They are NOT intended to make the engine choose these
         * openings itself.
         * =========================================================
         */

        /*
         * Stafford Gambit
         */
        add(
                "e4 e5",
                "Nf3 Nf6",
                "Nxe5 Nc6",
                "Nxc6 dxc6",
                "e5 Nd5",
                "d4"
        );

        /*
         * Stafford main line
         */
        add(
                "e4 e5",
                "Nf3 Nf6",
                "Nxe5 Nc6",
                "Nxc6 dxc6",
                "d3"
        );

        /*
         * Elephant Gambit
         */
        add(
                "e4 e5",
                "Nf3 d5",
                "exd5 e4",
                "Qe2 Qxd5",
                "Nc3"
        );

        /*
         * Latvian Gambit
         */
        add(
                "e4 e5",
                "Nf3 f5",
                "Nxe5 Qf6",
                "d4 d6",
                "Nc4 fxe4"
        );

        /*
         * Halloween Gambit
         */
        add(
                "e4 e5",
                "Nf3 Nc6",
                "Nc3 Nf6",
                "Nxe5 Nxe5",
                "d4"
        );

        /*
         * Blackmar Gambit
         */
        add(
                "d4 d5",
                "e4 dxe4",
                "Nc3 Nf6",
                "f3 exf3",
                "Nxf3"
        );

        /*
         * Blackmar Diemer
         */
        add(
                "d4 d5",
                "e4 dxe4",
                "Nc3 Nf6",
                "Bg5 Bf5",
                "f3"
        );

        /*
         * Englund trap response
         */
        add(
                "d4 e5",
                "dxe5 Nc6",
                "Nf3 Qe7",
                "Bf4 Qb4+",
                "Bd2 Qxb2",
                "Nc3"
        );

        /*
         * Wayward Queen Attack
         */
        add(
                "e4 e5",
                "Qh5 Nc6",
                "Bc4 Nf6",
                "Qxf7+ Ke7",
                "Qb3"
        );

        /*
         * Wayward Queen safer response
         */
        add(
                "e4 e5",
                "Qh5 Nc6",
                "Bc4 g6",
                "Qf3 Nf6",
                "Ne2"
        );

        /*
         * Scholar's Mate attempt
         */
        add(
                "e4 e5",
                "Bc4 Nc6",
                "Qh5 Nf6",
                "Qxf7+ Kxf7"
        );

        /*
         * =========================================================
         * EXTRA SICILIAN THEORY
         * =========================================================
         */

        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 e5",
                "Ndb5 a6",
                "Na3 b5"
        );

        add(
                "e4 c5",
                "Nf3 Nc6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 e5",
                "Ndb5 d6",
                "Bg5"
        );

        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 g6",
                "Be3 Bg7",
                "f3 O-O",
                "Qd2 Nc6"
        );

        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3 a6",
                "Be3 e6",
                "f3 Be7",
                "Qd2 O-O"
        );

        add(
                "e4 c5",
                "Nf3 e6",
                "d4 cxd4",
                "Nxd4 Nc6",
                "Nc3 Qc7",
                "Be2 a6"
        );

        /*
         * =========================================================
         * EXTRA FRENCH THEORY
         * =========================================================
         */

        add(
                "e4 e6",
                "d4 d5",
                "Nc3 Nf6",
                "Bg5 dxe4",
                "Nxe4 Be7",
                "Bxf6 Bxf6",
                "Nf3"
        );

        add(
                "e4 e6",
                "d4 d5",
                "Nc3 Bb4",
                "e5 c5",
                "a3 Bxc3+",
                "bxc3"
        );

        /*
         * =========================================================
         * EXTRA CARO KANN THEORY
         * =========================================================
         */

        add(
                "e4 c6",
                "d4 d5",
                "Nc3 dxe4",
                "Nxe4 Bf5",
                "Ng3 Bg6",
                "Nf3 Nd7",
                "h4 h6",
                "h5 Bh7"
        );

        add(
                "e4 c6",
                "d4 d5",
                "e5 Bf5",
                "Nf3 e6",
                "Be2 Nd7",
                "O-O Ne7"
        );

        /*
         * =========================================================
         * EXTRA QUEEN'S GAMBIT THEORY
         * =========================================================
         */

        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Be7",
                "e3 O-O",
                "Nf3 h6",
                "Bh4 b6"
        );

        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Nbd7",
                "e3 c6",
                "Nf3 Qa5"
        );

        /*
         * =========================================================
         * EXTRA SLAV THEORY
         * =========================================================
         */

        add(
                "d4 d5",
                "c4 c6",
                "Nf3 Nf6",
                "Nc3 dxc4",
                "a4 Bf5",
                "e3 e6",
                "Bxc4 Bb4"
        );

        add(
                "d4 d5",
                "c4 c6",
                "Nf3 Nf6",
                "Nc3 e6",
                "e3 Nbd7",
                "Bd3 dxc4",
                "Bxc4 Bb4"
        );

        /*
         * =========================================================
         * EXTRA KING'S INDIAN THEORY
         * =========================================================
         */

        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6",
                "Nf3 O-O",
                "Be2 e5",
                "O-O Nc6",
                "d5 Ne7"
        );

        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6",
                "f3 O-O",
                "Be3 e5",
                "d5 c6",
                "Qd2 cxd5"
        );

        /*
         * =========================================================
         * EXTRA NIMZO THEORY
         * =========================================================
         */

        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "Qc2 O-O",
                "a3 Bxc3+",
                "Qxc3 d5"
        );

        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "Nf3 O-O",
                "Bg5 d5",
                "e3 Nbd7"
        );

        /*
         * =========================================================
         * MORE MAINLINE E4 VARIATIONS
         * =========================================================
         */

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "Re1 b5",
                "Bb3 Be7",
                "c3 O-O",
                "h3"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "O-O Nxe4",
                "Re1 b5",
                "Bb3 d5",
                "Nxe5"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Nf6",
                "d3 Bc5",
                "O-O O-O",
                "c3 d6",
                "Re1"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Nc3 Nf6",
                "Bb5 Bb4",
                "O-O O-O",
                "d3 d6",
                "Bg5"
        );

        /*
         * =========================================================
         * EXTRA BLACK RESPONSES TO 1.e4
         *
         * These make sure Black has substantial theory after e4.
         * =========================================================
         */

        add(
                "e4 e5",
                "Nf3 Nc6"
        );

        add(
                "e4 c5",
                "Nf3 d6"
        );

        add(
                "e4 e6",
                "d4 d5"
        );

        add(
                "e4 c6",
                "d4 d5"
        );

        add(
                "e4 d5",
                "exd5 Qxd5"
        );

        add(
                "e4 d6",
                "d4 Nf6"
        );

        /*
         * =========================================================
         * EXTRA BLACK RESPONSES TO 1.d4
         * =========================================================
         */

        add(
                "d4 d5",
                "c4 e6"
        );

        add(
                "d4 Nf6",
                "c4 e6"
        );

        add(
                "d4 Nf6",
                "c4 g6"
        );

        add(
                "d4 f5",
                "c4 Nf6"
        );

        add(
                "d4 e5",
                "dxe5 Nc6"
        );

        /*
         * =========================================================
         * EXTRA ENGLISH
         * =========================================================
         */

        add(
                "c4 e5",
                "Nc3 Nf6"
        );

        add(
                "c4 Nf6",
                "Nc3 e6"
        );

        add(
                "c4 c5",
                "Nc3 Nc6"
        );

        /*
         * =========================================================
         * EXTRA RÉTI
         * =========================================================
         */

        add(
                "Nf3 d5",
                "g3 Nf6"
        );

        add(
                "Nf3 Nf6",
                "g3 g6"
        );

        /*
         * =========================================================
         * DEVELOPMENT CONTINUATIONS
         * =========================================================
         */

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bc4 Bc5",
                "O-O Nf6",
                "Re1 O-O"
        );

        add(
                "e4 e5",
                "Nf3 Nc6",
                "Bb5 a6",
                "Ba4 Nf6",
                "O-O Be7",
                "Re1 O-O"
        );

        add(
                "e4 c5",
                "Nf3 d6",
                "d4 cxd4",
                "Nxd4 Nf6",
                "Nc3"
        );

        add(
                "e4 e6",
                "d4 d5",
                "Nc3 Nf6",
                "Bg5 Be7",
                "e5 Nfd7"
        );

        add(
                "e4 c6",
                "d4 d5",
                "Nc3 dxe4",
                "Nxe4 Bf5"
        );

        add(
                "d4 d5",
                "c4 e6",
                "Nc3 Nf6",
                "Bg5 Be7"
        );

        add(
                "d4 Nf6",
                "c4 e6",
                "Nc3 Bb4",
                "e3 O-O"
        );

        add(
                "d4 Nf6",
                "c4 g6",
                "Nc3 Bg7",
                "e4 d6"
        );
    }

    /**
     * Add a complete opening line.
     *
     * Each argument can contain multiple SAN moves separated by
     * spaces.
     */
    private void add(String... moveGroups) {

        List<String> line =
                new ArrayList<>();

        for (String group : moveGroups) {

            if (group == null) {
                continue;
            }

            String[] moves =
                    group.trim().split("\\s+");

            line.addAll(
                    Arrays.asList(moves)
            );
        }

        if (!line.isEmpty()) {
            openings.add(line);
        }
    }

    /**
     * Return every opening line in the book.
     */
    public List<List<String>> getOpenings() {
        return openings;
    }
}