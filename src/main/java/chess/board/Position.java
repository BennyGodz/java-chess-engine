package chess.board;

import java.util.Objects;

/**
 * An immutable square on the board.
 *
 * <p>Row 0 is rank 8 and row 7 is rank 1; column 0 is file a and column 7 is file h.
 */
public class Position {

  private final int row;
  private final int column;

  public Position(int row, int column) {
    this.row = row;
    this.column = column;
  }

  /** Returns the row index (0 = rank 8, 7 = rank 1). */
  public int getRow() {
    return row;
  }

  /** Returns the column index (0 = file a, 7 = file h). */
  public int getColumn() {
    return column;
  }

  /** Returns whether this position lies on the board. */
  public boolean isValid() {
    return row >= 0 && row < 8 && column >= 0 && column < 8;
  }

  /** Returns the square in algebraic notation, e.g. "e4". */
  public String toAlgebraic() {
    if (!isValid()) {
      return "??";
    }
    char file = (char) ('a' + column);
    char rank = (char) ('8' - row);
    return "" + file + rank;
  }

  @Override
  public String toString() {
    return toAlgebraic();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof Position)) return false;
    Position position = (Position) other;
    return row == position.row && column == position.column;
  }

  @Override
  public int hashCode() {
    return Objects.hash(row, column);
  }
}
