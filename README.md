# Java Chess Engine

A console chess engine written in Java.

## Input

The engine accepts Standard Algebraic Notation (SAN), including:

- `e4`
- `Nf3`
- `Nbd2` / `Nfd2` when two knights can reach the same square
- `R1e2` / `R8e2` when rank disambiguation is required
- `Raxe2` when file disambiguation is required for a capture
- `Bxe6+`
- `Qh7#`
- `O-O` and `O-O-O`
- `e8=Q`, `e8=R`, `e8=B`, `e8=N`

Coordinate notation is also accepted for convenience:

- `e2e4`
- `e2-e4`
- `e2 e4`
- `e7e8Q`

## Implemented chess rules

- All six piece types
- Legal movement and captures
- Check and checkmate
- Stalemate
- Castling
- En passant
- Promotion
- Pinned pieces / self-check prevention
- SAN generation and parsing
- SAN disambiguation
- 50-move rule claim
- Threefold repetition claim
- Automatic 75-move draw
- Automatic fivefold-repetition draw
- Common insufficient-material draw detection
- FEN generation
- Legal move listing

## Commands

- `moves` — list all legal moves in SAN
- `fen` — print the current FEN
- `claim50` — claim the 50-move draw when available
- `claim3` — claim the threefold-repetition draw when available
- `help`
- `quit`

## Build

The project is a normal Gradle Java project. From the project directory:

```bash
./gradlew build
```

The source can also be compiled directly with `javac`.
