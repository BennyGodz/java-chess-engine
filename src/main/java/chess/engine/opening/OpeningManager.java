package chess.engine.opening;

import chess.board.Board;
import chess.board.Move;
import java.util.ArrayList;
import java.util.List;

/** Tracks opening-book state and deterministically chooses its strongest continuation. */
public class OpeningManager {

  private final OpeningBook openingBook = new OpeningBook();
  private final List<String> playedMoves = new ArrayList<>();
  private boolean openingActive = true;

  public OpeningManager() {}

  public void recordMove(String moveSan) {
    if (!openingActive) return;
    playedMoves.add(removeCheckSuffix(moveSan));
    if (getMatchingOpenings().isEmpty()) {
      deactivate("Opening book: Position is off theory. Switching to normal engine search.");
    }
  }

  public Move getOpeningMove(Board board) {
    if (!openingActive) return null;

    List<List<String>> matchingOpenings = getMatchingOpenings();
    if (matchingOpenings.isEmpty()) {
      return deactivate("Opening book: No matching theory. Switching to normal engine search.");
    }

    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());
    if (legalMoves.isEmpty()) return null;

    PrioritizedMove selected = null;
    for (Move move : legalMoves) {
      int priority = getMovePriority(board, move, matchingOpenings);
      if (priority > 0
          && (selected == null
              || priority > selected.priority()
              || priority == selected.priority()
                  && board.formatMove(move).compareTo(board.formatMove(selected.move())) < 0)) {
        selected = new PrioritizedMove(move, priority);
      }
    }

    if (selected == null) {
      return deactivate("Opening book: No book move available. Switching to normal engine search.");
    }

    System.out.println(
        "Opening book: "
            + board.formatMove(selected.move())
            + " [priority "
            + selected.priority()
            + "]");
    return selected.move();
  }

  private int getMovePriority(Board board, Move move, List<List<String>> matchingOpenings) {
    String moveSan = removeCheckSuffix(board.formatMove(move));
    int bestPriority = 0;
    boolean early = playedMoves.size() <= 8;

    for (List<String> opening : matchingOpenings) {
      int index = playedMoves.size();
      if (index >= opening.size()) continue;
      String expectedMove = removeCheckSuffix(opening.get(index));
      if (!expectedMove.equals(moveSan)) continue;

      int remainingMoves = opening.size() - index - 1;
      int priority = 100 + Math.min(remainingMoves * 3, 60);
      if (early) {
        if (moveSan.equals("e4") || moveSan.equals("d4")) priority += 15;
        if (moveSan.equals("c4") || moveSan.equals("Nf3")) priority += 5;
        if (moveSan.startsWith("Q")) priority -= 20;
        if (moveSan.startsWith("N")) priority -= 5;
      }
      bestPriority = Math.max(bestPriority, priority);
    }

    return Math.max(0, bestPriority);
  }

  private List<List<String>> getMatchingOpenings() {
    return openingBook.getOpenings().stream().filter(this::matchesPlayedMoves).toList();
  }

  private boolean matchesPlayedMoves(List<String> opening) {
    if (opening == null || opening.size() < playedMoves.size()) return false;
    for (int i = 0; i < playedMoves.size(); i++) {
      if (!removeCheckSuffix(opening.get(i)).equals(playedMoves.get(i))) return false;
    }
    return true;
  }

  public void disable() {
    if (openingActive) deactivate("Opening book disabled. Normal engine search will be used.");
  }

  public void reset() {
    playedMoves.clear();
    openingActive = true;
    System.out.println("Opening book reset.");
  }

  public boolean isOpeningActive() {
    return openingActive;
  }

  public boolean isActive() {
    return isOpeningActive();
  }

  public String getOpeningName() {
    if (!openingActive) return "None / Off Theory";
    if (playedMoves.isEmpty()) return "Opening Book";
    return "Opening Book: " + String.join(" ", playedMoves);
  }

  public int getMoveIndex() {
    return playedMoves.size();
  }

  public void advance() {}

  private Move deactivate(String message) {
    openingActive = false;
    System.out.println(message);
    return null;
  }

  private static String removeCheckSuffix(String san) {
    return san == null ? "" : san.replace("+", "").replace("#", "");
  }

  private record PrioritizedMove(Move move, int priority) {}
}
