# Java Chess Engine

A chess engine written in Java with a terminal game, an NNUE evaluator, a built-in opening book,
self-play training, PGN training, and a Lichess bot. The chess rules, move generation, search,
evaluation, and training code are written in this project instead of using a chess-engine library.
Jackson is used to read messages from the Lichess API.

## Requirements

- Java 21 or newer
- The included Gradle wrapper
- An internet connection only for downloading build files or PGNs and playing on Lichess

## Build and test

macOS/Linux:

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

The tests cover the board rules, the standard count of 8,902 positions after the first three turns
from the starting position, very short searches, and NNUE training helpers.

All Java sources are formatted with the included formatter:

```bash
java -jar google-java-format.jar --replace $(find src -name "*.java")
```

In PowerShell:

```powershell
$javaFiles = @(Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName)
java -jar google-java-format.jar --replace $javaFiles
```

## Terminal game

Compile the project, then start the terminal game:

```bash
./gradlew classes
java -cp build/classes/java/main chess.Main
```

On Windows, use `.\gradlew.bat classes` for the first command. The program asks whether you want
to play White or Black.

Accepted move formats include SAN and coordinate notation:

```text
e4       Nf3      Nbd2     R1e2
Bxe6+    O-O      O-O-O    e8=Q
e2e4     e2 e4    e7e8Q
```

Terminal commands:

- `moves` lists every legal move in SAN.
- `fen` prints the current FEN.
- `eval` searches the current position for one second.
- `claim50` claims a draw when the 50-move rule applies.
- `claim3` claims a draw after threefold repetition.
- `help` displays command help.
- `quit` exits the game.

The terminal engine searches to a maximum depth of 9 with a one-second move budget. Those defaults
are constants in `chess.Main` rather than command-line options.

## Search

`SearchEngine` uses:

- iterative deepening until the time limit is reached;
- alpha-beta search written in negamax form;
- principal variation search;
- a transposition table indexed with Zobrist hashes;
- null-move pruning;
- quiescence search for captures, promotions, and moves that escape check;
- killer moves and history scores to search promising moves first;
- small search extensions for checks and promotions; and
- an evaluated fallback move if time runs out before depth one finishes.

Search scores are measured in centipawns for the player whose turn it is. Checkmate scores include
the number of moves to mate, so the engine prefers a faster checkmate.

## Evaluation and NNUE

The evaluator combines the trained NNUE score with piece values, a small bonus for the player whose
turn it is, and simple endgame rules that push a losing king toward the edge. The NNUE has this
layout:

```text
769 inputs -> 256 ReLU -> 256 ReLU -> 128 ReLU -> tanh output
```

Its inputs describe where the pieces and kings are, the pawn structure, king safety, castling
rights, en passant, and whose turn it is. The network returns a score in centipawns for the player
whose turn it is.

At startup, weights are loaded in this order:

1. `nnue_weights_best.bin`
2. `nnue_weights.bin`
3. `nnue.weights`

If none can be loaded, the NNUE returns zero. Material and endgame evaluation still work, so
missing weights do not cause random move scores.

## Training

Training is game-based. `GameTrainer` reads PGNs already under `games/`; it does not generate or
download games. The trainer replays each game and turns its positions into NNUE inputs. Duplicate
positions are removed, positions are also mirrored with the colors swapped, and the data is split
into fixed training and validation sets.

Self-play PGNs attach a search score to each searched move:

```text
1. e4 { ev 24 depth 8 } e5 { ev 11 depth 9 }
```

Each training target combines the engine's search score with the game result. Positions closer to
the end of a game use more of the final result. PGNs without search scores are still used, but with
less weight. Scores from deeper searches count more, old invalid timeout scores are ignored, and
games without search scores are limited so they cannot outweigh the better data. The trainer uses
all non-self-play PGNs and the newest 64 self-play batches, so old scores from weaker networks do
not outweigh newer training data.

The trainer uses AdamW, limits unusually large updates, uses dropout, lowers the learning rate when
progress stalls, and stops early when validation stops improving. Training writes:

- `nnue_weights.bin`: the best network from the current run;
- `nnue_weights_best.bin`: the best network across every run, which the engine loads first;
- `training_state.txt`: the best saved results; and
- `training_history.log`: a record of completed training runs.

### Generate self-play games

```bash
java -cp build/classes/java/main chess.engine.training.SelfPlayGenerator [games] [timeMs] [threads]
```

Defaults are 256 games, 400 ms per searched move, and half the available CPU threads. Output is
written to `games/selfplay/`. Opening moves come from the built-in book; every later move is chosen
by search, not randomly. Opening lines are shuffled and used evenly. The number of opening-book
moves changes between games to create different positions without adding random moves.

Example:

```bash
java -cp build/classes/java/main chess.engine.training.SelfPlayGenerator 512 300 8
```

### Download Lichess PGNs

```bash
java -cp build/classes/java/main chess.engine.training.LichessGameDownloader [gamesPerPlayer]
```

The default is 500 games for each player listed in the downloader. Files are written to
`games/lichess/`. This step is optional; any valid PGN files can be placed anywhere under `games/`.

### Train existing data once

```bash
java -cp build/classes/java/main chess.engine.training.GameTrainer [epochs]
```

The default maximum is 60 epochs. The trainer stops with an error when fewer than eight usable
games are available. Override the input directory with `-Dgames.root=/path/to/games` before the
class name if needed.

### Run the full training loop

```bash
java -cp build/classes/java/main chess.engine.training.TrainingPipeline \
  [hours] [gamesPerIteration] [timeMs] [threads] [epochs]
```

Current defaults are 12 hours, 256 games in the first iteration, 400 ms per searched move, half the
available CPU threads, and at most 16 epochs per training stage. Each round generates new self-play
games using the current best network, trains the NNUE, keeps a new network only when validation
improves, and repeats until time runs out. The number of games grows by 20% each round up to four
times the starting count. Move time grows by 10% up to three times the starting time. The pipeline
does not start another larger round when the previous round would not fit in the time left. At least
100 games are required for each round.

Example:

```bash
java -cp build/classes/java/main chess.engine.training.TrainingPipeline 8 512 300 8 16
```

Training can improve the evaluator, but no Elo level is guaranteed. Strength depends on search
time, the computer running it, the games used for training, and the saved network.

## Opening book

`OpeningBook` contains a list of opening lines written in SAN. `OpeningManager` tracks every line
that matches the game so far and always chooses the highest-priority next move. It stops using the
book when the game leaves those saved openings.

The terminal game also compares a proposed book move with a short normal search and rejects
the book move when it is more than 50 centipawns worse. After book play ends, all engine moves come
from `SearchEngine`.

## Chess rules and notation

The engine supports:

- legal moves and captures for all six piece types;
- check, checkmate, stalemate, pins, and self-check prevention;
- castling, en passant, and all four promotion choices;
- reading and writing SAN, including moves where two matching pieces could use the same square;
- FEN loading and generation;
- 50-move and threefold-repetition draws that a player can claim;
- automatic draws after 75 moves or five repetitions; and
- draws when neither side has enough pieces to checkmate.

## Lichess bot

`LichessBot` accepts challenges, follows live games, rebuilds the board from UCI moves, searches,
and sends moves through the Lichess Bot API. Set a bot-account token before starting it:

macOS/Linux:

```bash
export LICHESS_TOKEN="your-token"
```

Windows PowerShell:

```powershell
$env:LICHESS_TOKEN = "your-token"
```

Run `chess.lichess.LichessBot.main()` from IntelliJ IDEA or another launcher that includes the
Gradle dependencies because the Lichess code needs Jackson. `LichessGame.getMyBotId()` currently
returns `bugabot`; change that method when using a different Lichess bot account.

## Project structure

```text
src
├── main/java/chess
│   ├── Main.java
│   ├── board
│   │   ├── Board.java
│   │   ├── Move.java
│   │   └── Position.java
│   ├── pieces
│   │   ├── Piece.java
│   │   ├── Pawn.java
│   │   ├── Knight.java
│   │   ├── Bishop.java
│   │   ├── Rook.java
│   │   ├── Queen.java
│   │   └── King.java
│   ├── engine
│   │   ├── MoveGenerator.java
│   │   ├── evaluation
│   │   │   ├── Evaluator.java
│   │   │   └── nnue
│   │   ├── opening
│   │   ├── search
│   │   └── training
│   └── lichess
└── test/java/chess
    ├── board
    └── engine
```

Generated PGNs, saved NNUE files, and local build output are excluded from Git.

## References and further reading

The code was written for this project. These resources helped explain some of the ideas and tools
used while developing it.

### Chess-engine concepts

- [Alpha-Beta Pruning in Adversarial Search Algorithms](https://www.geeksforgeeks.org/artificial-intelligence/alpha-beta-pruning-in-adversarial-search-algorithms/)
  gives an introductory explanation of alpha-beta search.
- Sebastian Lague's [Coding Adventure: Chess](https://www.youtube.com/watch?v=U4ogK0MIzqk)
  shows move generation, testing, search, evaluation, transposition tables, and openings.
- His follow-up,
  [Coding Adventure: Making a Better Chess Bot](https://www.youtube.com/watch?v=_vqlIPDR2TU),
  covers stronger move selection, search extensions, bitboards, faster move generation,
  killer moves, reductions, repetition handling, and connecting a bot to Lichess.
- [Implementing Quiescence Search](https://www.youtube.com/watch?v=WzEhVjdNByg) explains why the
  engine continues checking captures in unstable positions instead of stopping too early.
- The [Chess Programming Wiki: Quiescence Search](https://chessprogramming.org/Quiescence_Search)
  page provides more details and example code.
- [Magic Bitboards](https://chessprogramming.org/Magic_Bitboards) is further reading for a possible
  future way to generate bishop, rook, and queen moves faster. This engine does not currently use
  bitboards or magic bitboards.

### NNUE and computer-chess projects

- The [Stockfish project](https://github.com/official-stockfish/Stockfish) is a leading open-source
  chess engine and a useful example of modern engine design and testing.
- Stockfish's
  [NNUE technical documentation](https://github.com/official-stockfish/nnue-pytorch/blob/master/docs/nnue.md)
  explains how an NNUE reads positions, is trained, and evaluates positions quickly.

### API and project tooling

- The [official Lichess API specification](https://github.com/lichess-org/api/blob/master/doc/specs/lichess-api.yaml)
  documents event streams, challenges, game streams, PGN downloads, and bot move submission.
- The [Gradle Wrapper documentation](https://docs.gradle.org/current/userguide/wrapper_plugin.html)
  explains how the included scripts use the same Gradle version on every computer.
- The [google-java-format project](https://github.com/google/google-java-format) is the formatter
  used for all Java source files.

## Known limitations

- Search is single-threaded within one game; self-play parallelism runs independent games.
- There are no endgame tablebases, bitboards, or magic-bitboard move generation.
- The opening book is a built-in list rather than a full opening database.
- NNUE training uses the engine's own search evaluations rather than an external teacher.
- Lichess color detection currently assumes the bot account is named `bugabot`.

## Use of AI tools

AI tools were used to help learn chess-engine ideas, compare ways to implement features, find bugs,
clean up code, and improve the documentation. Suggestions were reviewed, changed when needed, and
tested before they were used.

## Author

**Benny Yampolskiy**
