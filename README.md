# Java Chess Engine

A chess engine written in Java with a terminal interface, an NNUE evaluator, a built-in opening
book, self-play training, PGN training, and Lichess bot integration. Chess rules, move generation,
search, evaluation, and training are implemented in this project rather than delegated to a chess
engine library. Jackson is used to process Lichess API events.

## Requirements

- Java 21 or newer
- The included Gradle wrapper
- An internet connection only for dependency resolution, Lichess play, or downloading PGNs

## Build and test

macOS/Linux:

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

The tests cover core board behavior, the standard depth-three starting-position perft count of
8,902 positions, short-time search fallback behavior, and NNUE training utilities.

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

Compile the project, then start the terminal interface:

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

- iterative deepening with a strict time limit;
- negamax alpha-beta search;
- principal variation search;
- a Zobrist-keyed transposition table;
- null-move pruning;
- quiescence search for captures and promotions, plus every legal evasion while in check;
- killer moves and a history heuristic;
- bounded check and promotion extensions; and
- deterministic static fallback selection when the time budget expires before depth one finishes.

Search scores are centipawns from the perspective of the side to move. Checkmate scores use a large
fixed mate value and include ply distance so faster mates are preferred.

## Evaluation and NNUE

The evaluator combines the trained NNUE score with material, a tempo bonus, and simple king-driving
endgame terms. The NNUE has this architecture:

```text
769 inputs -> 256 ReLU -> 256 ReLU -> 128 ReLU -> tanh output
```

Its inputs describe piece-square placement, king location, pawn structure, king safety, castling
rights, en passant availability, and the side to move. The network produces a side-to-move score
scaled to centipawns.

At startup, weights are loaded in this order:

1. `nnue_weights_best.bin`
2. `nnue_weights.bin`
3. `nnue.weights`

If none can be loaded, the NNUE becomes a deterministic zero-output network. Material and endgame
evaluation remain active, so missing weights do not introduce random move scores.

## Training

Training is game-based. `GameTrainer` reads PGNs already under `games/`; it does not generate or
download games. Positions are replayed, converted to sparse NNUE features, deduplicated, augmented
by color-swapped mirroring, and split into stable training and validation sets.

Self-play PGNs attach a search score to each searched move:

```text
1. e4 { ev 24 } e5 { ev 11 }
```

The label blends that side-to-move search evaluation with a temporally weighted game result. PGNs
without evaluation comments remain usable at lower weight. Invalid legacy timeout scores are
rejected, and result-only examples are limited so they cannot overwhelm evaluation-backed data.

The optimizer is AdamW-style Adam with gradient clipping, dropout, learning-rate reduction,
validation checkpoints, and early stopping. Training writes:

- `nnue_weights.bin`: the working checkpoint;
- `nnue_weights_best.bin`: the protected best checkpoint loaded first by the engine;
- `training_state.txt`: cross-run best metrics; and
- `training_history.log`: append-only training history.

### Generate self-play games

```bash
java -cp build/classes/java/main chess.engine.training.SelfPlayGenerator [games] [timeMs] [threads]
```

Defaults are 256 games, 200 ms per searched move, and half the available processors. Output is
written to `games/selfplay/`. Opening moves come from the built-in book; every later move is chosen
by search, not randomly.

Example:

```bash
java -cp build/classes/java/main chess.engine.training.SelfPlayGenerator 512 300 8
```

### Download Lichess PGNs

```bash
java -cp build/classes/java/main chess.engine.training.LichessGameDownloader [gamesPerPlayer]
```

The default is 500 games per configured player. Files are written to `games/lichess/`. This step is
optional; any valid PGN files can be placed anywhere under `games/`.

### Train existing data once

```bash
java -cp build/classes/java/main chess.engine.training.GameTrainer [epochs]
```

The default maximum is 60 epochs. The trainer stops with an error when fewer than eight usable
games are available. Override the input directory with `-Dgames.root=/path/to/games` before the
class name if needed.

### Run the full pipeline

```bash
java -cp build/classes/java/main chess.engine.training.TrainingPipeline \
  [hours] [gamesPerIteration] [timeMs] [threads] [epochs]
```

Current defaults are 12 hours, 256 games per iteration, 200 ms per searched move, half the
available processors, and at most 16 epochs per training stage. Each iteration generates fresh
self-play games using the current checkpoint, trains on all PGNs under `games/`, protects the best
validation checkpoint, and repeats until the time window expires. At least 100 games per iteration
are required.

Example:

```bash
java -cp build/classes/java/main chess.engine.training.TrainingPipeline 8 512 300 8 16
```

Training can improve the evaluator, but no Elo level is guaranteed. Strength depends on search
time, hardware, training-data quality, validation quality, and the resulting checkpoint.

## Opening book

`OpeningBook` contains prepared SAN move sequences. `OpeningManager` follows every line compatible
with the moves played so far and deterministically chooses the highest-priority continuation. It
leaves book mode when the game moves outside known theory.

The terminal interface also compares a proposed book move with a short normal search and rejects
the book move when it is more than 50 centipawns worse. After book play ends, all engine moves come
from `SearchEngine`.

## Chess rules and notation

The board implementation supports:

- legal moves and captures for all six piece types;
- check, checkmate, stalemate, pins, and self-check prevention;
- castling, en passant, and all four promotion choices;
- SAN parsing, formatting, and disambiguation;
- FEN loading and generation;
- claimable 50-move and threefold-repetition draws;
- automatic 75-move and fivefold-repetition draws; and
- insufficient-material detection.

## Lichess bot

`LichessBot` accepts challenges, streams games, reconstructs boards from UCI moves, searches, and
submits moves through the Lichess Bot API. Set a bot-account token before launching it:

macOS/Linux:

```bash
export LICHESS_TOKEN="your-token"
```

Windows PowerShell:

```powershell
$env:LICHESS_TOKEN = "your-token"
```

Run `chess.lichess.LichessBot.main()` from IntelliJ IDEA or another launcher that uses the Gradle
runtime classpath, because the Lichess classes require Jackson. The bot ID used for color detection
is currently returned by `LichessGame.getMyBotId()` as `bugabot`; update that method when using a
different Lichess bot account.

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

Generated PGNs, NNUE checkpoints, and local build output are excluded from Git.

## Known limitations

- Search is single-threaded within one game; self-play parallelism runs independent games.
- There are no endgame tablebases, bitboards, or magic-bitboard move generation.
- The opening book is a built-in list rather than a Polyglot database.
- NNUE training uses the engine's own search evaluations rather than an external teacher.
- Lichess color detection currently assumes the bot account is named `bugabot`.

## AI-use disclosure

AI tools were used as learning and development aids to understand chess-engine concepts, explore
implementation approaches, troubleshoot problems, refactor code, and improve documentation. The
generated suggestions were reviewed, adapted, and tested as part of developing this project.

## Author

**Benny Yampolskiy**
