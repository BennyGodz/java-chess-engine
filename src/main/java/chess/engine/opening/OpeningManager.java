package chess.engine.opening;

import chess.board.Board;
import chess.board.Move;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Opening book manager supporting both White and Black.
 *
 * <p>The OpeningBook contains the actual opening lines.
 *
 * <p>This manager does not randomly choose between all matching moves anymore. Instead, it gives
 * priority to stronger opening lines and only uses randomness when moves have similar priority.
 *
 * <p>The goal is to keep the engine varied while strongly preferring good opening moves.
 */
public class OpeningManager {

  private final OpeningBook openingBook;
  private final Random random;
  private final List<String> playedMoves;

  private boolean openingActive;

  public OpeningManager() {

    this.openingBook = new OpeningBook();

    this.random = new Random();

    this.playedMoves = new ArrayList<>();

    this.openingActive = true;
  }

  /**
   * Record a move played on the board.
   *
   * <p>This should be called for EVERY move played by either side.
   *
   * @param moveSan SAN representation of the move
   */
  public void recordMove(String moveSan) {

    if (!openingActive) {
      return;
    }

    String cleanSan = removeCheckSuffix(moveSan);

    playedMoves.add(cleanSan);

    /*
     * Check whether at least one opening line
     * still matches the current position.
     */
    if (getMatchingOpenings().isEmpty()) {
      System.out.println(
          "Opening book: Position is off theory. " + "Switching to normal engine search.");
      openingActive = false;
    }
  }

  /**
   * Gets the best opening move for the current position.
   *
   * <p>Instead of randomly selecting from every matching line, moves are scored according to the
   * quality of the opening line they belong to.
   *
   * <p>A small amount of randomness is kept between moves that have similar priority.
   */
  public Move getOpeningMove(Board board) {

    if (!openingActive) {
      return null;
    }

    List<List<String>> matchingOpenings = getMatchingOpenings();

    if (matchingOpenings.isEmpty()) {
      System.out.println(
          "Opening book: No matching theory. " + "Switching to normal engine search.");
      openingActive = false;
      return null;
    }

    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

    if (legalMoves.isEmpty()) {
      return null;
    }

    /*
     * Each legal book move receives a priority.
     */
    List<PrioritizedMove> candidates = new ArrayList<>();

    for (Move move : legalMoves) {
      int priority = getMovePriority(board, move, matchingOpenings);
      if (priority > 0) {
        candidates.add(new PrioritizedMove(move, priority));
      }
    }

    /*
     * No book move matched the current position.
     */
    if (candidates.isEmpty()) {
      System.out.println(
          "Opening book: No book move available. " + "Switching to normal engine search.");
      openingActive = false;
      return null;
    }

    /*
     * Find the highest priority.
     */
    int highestPriority = 0;

    for (PrioritizedMove candidate : candidates) {
      if (candidate.priority > highestPriority) {
        highestPriority = candidate.priority;
      }
    }

    /*
     * Only keep moves close to the best priority.
     *
     * This prevents a very weak line from being selected
     * just because it happens to be in the book.
     *
     * A move must be within 10 priority points of the
     * strongest move to receive randomness.
     */
    List<PrioritizedMove> strongMoves = new ArrayList<>();

    for (PrioritizedMove candidate : candidates) {
      if (candidate.priority >= highestPriority - 10) {
        strongMoves.add(candidate);
      }
    }

    /*
     * Randomly choose only between strong moves.
     *
     * This gives variety without making the engine choose
     * obviously worse book moves.
     */
    PrioritizedMove selected = strongMoves.get(random.nextInt(strongMoves.size()));

    Move selectedMove = selected.move;

    System.out.println(
        "Opening book: "
            + board.formatMove(selectedMove)
            + " [priority "
            + selected.priority
            + ", "
            + strongMoves.size()
            + " strong choices]");

    return selectedMove;
  }

  /**
   * Determine the priority of a legal move.
   *
   * <p>The longer an opening line continues after the current position, the more established that
   * line is considered.
   *
   * <p>This means:
   *
   * <p>e4
   *
   * <p>from a long well defined line gets more priority than a move that only appears in a very
   * short line.
   */
  private int getMovePriority(Board board, Move move, List<List<String>> matchingOpenings) {

    String moveSan = removeCheckSuffix(board.formatMove(move));

    int bestPriority = 0;

    for (List<String> opening : matchingOpenings) {
      int index = playedMoves.size();
      if (index >= opening.size()) {
        continue;
      }
      String expectedMove = removeCheckSuffix(opening.get(index));
      if (!expectedMove.equals(moveSan)) {
        continue;
      } /* * How much theory remains after this move? */
      int remainingMoves = opening.size() - index - 1; /* * Base priority. */
      int priority =
          100; /* * Longer established lines get more priority. * * Cap this so extremely long lines do not * completely dominate everything else. */
      priority +=
          Math.min(
              remainingMoves * 3,
              60); /* * Prefer central pawn moves early. * * This is only a tie breaker. */
      if (isEarlyGame()) {
        if (moveSan.equals("e4") || moveSan.equals("d4")) {
          priority += 15;
        }
        if (moveSan.equals("c4") || moveSan.equals("Nf3")) {
          priority += 5;
        }
      } /* * Discourage early queen movement. */
      if (isEarlyGame() && moveSan.startsWith("Q")) {
        priority -= 20;
      } /* * Discourage repeated early knight moves. * * This specifically helps prevent the engine from * constantly choosing lines such as Nc6 / Nf6 merely * because they appear in many opening lines. */
      if (isEarlyGame() && isKnightMove(moveSan)) {
        priority -= 5;
      }
      if (priority > bestPriority) {
        bestPriority = priority;
      }
    }

    return Math.max(0, bestPriority);
  }

  /** Determine whether the game is still in the early opening. */
  private boolean isEarlyGame() {

    return playedMoves.size() <= 8;
  }

  /** Check whether SAN represents a knight move. */
  private boolean isKnightMove(String san) {

    return san != null && san.startsWith("N");
  }

  /** Checks whether a move is already in the list. */
  private boolean containsSameMove(List<Move> moves, Move target, Board board) {

    String targetSan = removeCheckSuffix(board.formatMove(target));

    for (Move move : moves) {
      String moveSan = removeCheckSuffix(board.formatMove(move));
      if (moveSan.equals(targetSan)) {
        return true;
      }
    }

    return false;
  }

  /** Returns every opening line that matches the moves played so far. */
  private List<List<String>> getMatchingOpenings() {

    List<List<String>> matching = new ArrayList<>();

    List<List<String>> openings = openingBook.getOpenings();

    if (openings == null) {
      return matching;
    }

    for (List<String> opening : openings) {
      if (matchesPlayedMoves(opening)) {
        matching.add(opening);
      }
    }

    return matching;
  }

  /** Checks whether an opening contains all moves played so far. */
  private boolean matchesPlayedMoves(List<String> opening) {

    if (opening == null) {
      return false;
    }

    if (opening.size() < playedMoves.size()) {
      return false;
    }

    for (int i = 0; i < playedMoves.size(); i++) {
      String openingMove = removeCheckSuffix(opening.get(i));
      String playedMove = removeCheckSuffix(playedMoves.get(i));
      if (!openingMove.equals(playedMove)) {
        return false;
      }
    }

    return true;
  }

  /** Disable the opening book manually. */
  public void disable() {

    if (openingActive) {
      openingActive = false;
      System.out.println("Opening book disabled. " + "Normal engine search will be used.");
    }
  }

  /**
   * Reset the opening manager for a new game.
   *
   * <p>Clears all previously recorded moves and enables the opening book again.
   */
  public void reset() {

    playedMoves.clear();

    openingActive = true;

    System.out.println("Opening book reset.");
  }

  /** Returns whether the opening book is active. */
  public boolean isOpeningActive() {
    return openingActive;
  }

  /** Backwards compatibility. */
  public boolean isActive() {
    return isOpeningActive();
  }

  /** Returns a description of the current book position. */
  public String getOpeningName() {

    if (!openingActive) {
      return "None / Off Theory";
    }

    if (playedMoves.isEmpty()) {
      return "Opening Book";
    }

    return "Opening Book: " + String.join(" ", playedMoves);
  }

  /** Returns the number of moves recorded. */
  public int getMoveIndex() {
    return playedMoves.size();
  }

  /** Kept for compatibility. */
  public void advance() {
    // Nothing needed.
  }

  /** Removes check and mate suffixes. */
  private String removeCheckSuffix(String san) {

    if (san == null) {
      return "";
    }

    return san.replace("+", "").replace("#", "");
  }

  /** Move with its opening priority. */
  private static class PrioritizedMove {

    private final Move move;
    private final int priority;

    private PrioritizedMove(Move move, int priority) {
      this.move = move;
      this.priority = priority;
    }
  }
}
