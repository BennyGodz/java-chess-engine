# Java Chess Engine

A chess engine written entirely in Java with support for a terminal interface, an opening book, and direct Lichess integration. The project was built from scratch without relying on external chess libraries. Every major component -- move generation, rule enforcement, search, evaluation, and communication with the Lichess API -- was implemented manually.

## Features

### Search

The engine uses several search techniques to improve move quality and reduce the number of positions that must be evaluated.

- Minimax search
- Alpha-beta pruning
- Quiescence search
- Move ordering
- Time controlled search
- Depth limited search

### Evaluation

The evaluation function combines material and positional factors.

- Material evaluation
- Piece-square tables
- Center control
- Piece activity
- Development bonuses
- Reduced early queen movement
- Reduced early knight overdevelopment

### Opening Book

The engine includes an opening book that selects a prepared opening at the beginning of each game.

- Prepared opening lines
- Multiple opening variations
- Automatic synchronization with terminal games
- Tactical verification before playing a book move
- Automatic fallback to normal search if the opponent deviates from the selected line

### Terminal Interface

The terminal version supports both Standard Algebraic Notation and coordinate notation.

## Input

The engine accepts Standard Algebraic Notation (SAN), including:

- `e4`
- `Nf3`
- `Nbd2`
- `R1e2`
- `Bxe6+`
- `O-O`
- `O-O-O`
- `e8=Q`

Coordinate notation is also accepted:

- `e2e4`
- `e2 e4`
- `e7e8Q`

## Implemented Chess Rules

- All six piece types
- Legal move generation
- Legal captures
- Check detection
- Checkmate detection
- Stalemate detection
- Castling
- En passant
- Promotion
- Pinned pieces
- Self-check prevention
- SAN generation
- SAN parsing
- SAN disambiguation
- Threefold repetition
- Fivefold repetition
- Fifty-move rule
- Seventy-five-move rule
- Insufficient material detection
- FEN generation
- FEN loading
- Legal move listing

## Lichess Integration

The engine can connect directly to a Lichess bot account.

Supported features include:

- Challenge acceptance
- Real-time game streaming
- Automatic board reconstruction
- UCI move conversion
- Automatic move submission
- Bot play in blitz games
- Opening book support during online games

## Project Structure

```text
src/main/java/chess
├── Main.java
├── board
│   ├── Board.java
│   └── Position.java
├── pieces
│   ├── Bishop.java
│   ├── King.java
│   ├── Knight.java
│   ├── Pawn.java
│   ├── Queen.java
│   └── Rook.java
├── engine
│   ├── ChessEngine.java
│   ├── evaluation
│   ├── opening
│   └── search
└── lichess
    ├── LichessBot.java
    ├── LichessClient.java
    └── LichessGame.java
```

## Commands

- `moves` -- list all legal moves in SAN
- `fen` -- print the current FEN
- `eval` -- run the search engine and display the current evaluation
- `claim50` -- claim a 50-move draw
- `claim3` -- claim a threefold repetition draw
- `help` -- print help information
- `quit` -- exit the program

## Search Algorithm

### Minimax

The engine assumes that both players will choose the strongest available move.

### Alpha-Beta Pruning

Alpha-beta pruning eliminates branches that cannot improve the current position.

Benefits include:

- Faster searches
- Fewer evaluated positions
- Greater search depth

### Quiescence Search

Quiescence search extends the search beyond the normal depth limit during tactical positions.

This reduces the horizon effect and improves tactical accuracy.

## Future Improvements

- Iterative deepening
- Transposition tables
- Zobrist hashing
- Magic bitboards
- Null move pruning
- Late move reductions
- Endgame tablebases
- Parallel search

## Build

The project uses Gradle.

Build the project:

```bash
./gradlew build
```

Run the terminal version:

```bash
./gradlew run
```

The source can also be compiled directly with `javac`.

## Example

```text
=============================
        JAVA CHESS ENGINE
=============================

You are playing White.

8 r n b q k b n r
7 p p p p p p p p
6 . . . . . . . .
5 . . . . P . . .
4 . . . . . . . .
3 . . . . . . . .
2 P P P P . P P P
1 R N B Q K B N R
  a b c d e f g h

Move 1: In progress

Engine is thinking...

Engine plays Nf6 [depth 9, nodes 245,318]
```

## Author

**Benny Yampolskiy**

Java Chess Engine -- built from scratch without external chess libraries.