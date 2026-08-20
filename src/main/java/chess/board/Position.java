package chess.board;

import java.util.Objects;

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
