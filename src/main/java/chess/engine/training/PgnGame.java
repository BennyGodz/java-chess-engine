package chess.engine.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single chess game parsed from PGN text.
 *
 * <p>Learning in this project is GAME based: one PGN supplies every training example of that game,
 * and all positions inherit the game's final result.
 */
public final class PgnGame {

  /** Game result token: "1-0", "0-1", "1/2-1/2" or "*". */
  private final String result;

  /** SAN moves of the game, without move numbers, comments or variations. */
  private final List<String> sanMoves;

  private final Map<String, String> headers;

  private PgnGame(String result, List<String> sanMoves, Map<String, String> headers) {
    this.result = result;
    this.sanMoves = sanMoves;
    this.headers = headers;
  }

  public String getResult() {
    return result;
  }

  public List<String> getSanMoves() {
    return sanMoves;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  /**
   * Splits a raw PGN stream (possibly containing many games) into individual games and parses each
   * one.
   */
  public static List<PgnGame> parseAll(String pgnText) {
    List<PgnGame> games = new ArrayList<>();

    StringBuilder current = new StringBuilder();
    for (String line : pgnText.split("\\R")) {
      if (line.trim().startsWith("[Event ") && current.length() > 0) {
        PgnGame game = parseSingle(current.toString());
        if (game != null) games.add(game);
        current.setLength(0);
      }
      current.append(line).append('\n');
    }
    if (current.length() > 0) {
      PgnGame game = parseSingle(current.toString());
      if (game != null) games.add(game);
    }

    return games;
  }

  /** Parses one PGN game. Returns null when no usable movetext is present. */
  public static PgnGame parseSingle(String pgn) {
    String result = "*";
    Map<String, String> headers = new LinkedHashMap<>();
    Matcher headerMatcher = headerPattern.matcher(pgn);
    while (headerMatcher.find()) {
      String tag = headerMatcher.group(1);
      String value = headerMatcher.group(2);
      headers.put(tag, value);
      if (tag.equals("Result")) result = value;
    }

    /*
     * Strip everything that is not part of the mainline movetext.
     */
    String movetext = pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");
    movetext = movetext.replaceAll("\\{[^}]*}", " ");
    for (int i = 0; i < 5; i++) movetext = movetext.replaceAll("\\([^()]*\\)", " ");
    movetext = movetext.replaceAll(";[^\n]*", " ");
    movetext = movetext.replaceAll("\\$\\d+", " ");

    List<String> moves = new ArrayList<>();
    for (String token : movetext.trim().split("\\s+")) {
      if (token.isEmpty()) continue;
      if (isResultToken(token)) continue;
      token = token.replaceFirst("^\\d+\\.{1,3}", "");
      if (token.isEmpty()) continue;
      if (!looksLikeSan(token)) continue;
      moves.add(token);
    }

    if (moves.isEmpty()) return null;
    return new PgnGame(result, moves, headers);
  }

  private static final Pattern headerPattern =
      Pattern.compile("^\\s*\\[(\\w+)\\s+\"([^\"]*)\"\\]", Pattern.MULTILINE);

  private static boolean isResultToken(String token) {
    return token.equals("1-0")
        || token.equals("0-1")
        || token.equals("1/2-1/2")
        || token.equals("*");
  }

  private static boolean looksLikeSan(String token) {
    return token.matches(
        "^(O-O(-O)?|0-0(-0)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=?[QRBNqrbn])?)[+#?!]*$");
  }
}
