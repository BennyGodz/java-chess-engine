package chess.board;

/** Immutable board square using rows 0..7 for ranks 8..1 and columns 0..7 for files a..h. */
public class Position {

  private final int row;
  private final int column;

  public Position(int row, int column) {
    this.row = row;
    this.column = column;
  }

  public int getRow() {
    return row;
  }

  public int getColumn() {
    return column;
  }

  public boolean isValid() {
    return row >= 0 && row < 8 && column >= 0 && column < 8;
  }

  public String toAlgebraic() {
    return isValid() ? "" + (char) ('a' + column) + (char) ('8' - row) : "??";
  }

  @Override
  public String toString() {
    return toAlgebraic();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    return other instanceof Position position && row == position.row && column == position.column;
  }

  @Override
  public int hashCode() {
    return 31 * row + column;
  }
}
