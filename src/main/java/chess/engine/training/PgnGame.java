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
 *
 * <p>Self-play games additionally carry a per-move search score written as a comment directly after
 * the SAN token ({@code e4 { ev 34 }}). The value is centipawns from the perspective of the side
 * that moved, i.e. an evaluation of the position the move was played FROM. These comments are
 * optional; {@link #getEvalCp()} returns {@code NaN} for moves without one.
 */
public final class PgnGame {

  /** Matches the self-play evaluation comment format {@code { ev <number> }}. */
  private static final Pattern EVAL_COMMENT_PATTERN =
          Pattern.compile("\\{\\s*ev\\s+(-?\\d+(?:\\.\\d+)?)\\s*}");

  /** One mainline token or brace comment at a time, preserving document order. */
  private static final Pattern MOVENTEXT_TOKEN_PATTERN = Pattern.compile("\\{[^}]*\\}|\\S+");

  /** Game result token: "1-0", "0-1", "1/2-1/2" or "*". */
  private final String result;

  /** SAN moves of the game, without move numbers, comments or variations. */
  private final List<String> sanMoves;

  /**
   * Search score in centipawns (side-that-moved perspective) per SAN move, or {@code NaN} when the
   * move carries no evaluation comment. Same length as {@link #sanMoves}.
   */
  private final double[] evalCp;

  private final Map<String, String> headers;

  private PgnGame(
          String result, List<String> sanMoves, double[] evalCp, Map<String, String> headers) {
    this.result = result;
    this.sanMoves = sanMoves;
    this.evalCp = evalCp;
    this.headers = headers;
  }

  public String getResult() {
    return result;
  }

  public List<String> getSanMoves() {
    return sanMoves;
  }

  /** Per-move search scores in centipawns ({@code NaN} where absent); aligned with the SAN list. */
  public double[] getEvalCp() {
    return evalCp;
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
     * Strip everything that is not part of the mainline movetext. Parenthesized variations are
     * removed innermost-first so moves and evaluation comments inside them cannot disturb the
     * mainline alignment; rest-of-line ';' comments and '$' NAGs go too.
     */
    String movetext = pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");
    movetext = movetext.replaceAll(";[^\n]*", " ");
    movetext = movetext.replaceAll("\\$\\d+", " ");
    for (int i = 0; i < 5; i++) movetext = movetext.replaceAll("\\([^()]*\\)", " ");

    /*
     * Single left-to-right scan over mainline tokens AND brace comments, so an evaluation comment
     * attaches to the SAN move immediately BEFORE it. Book and random opening moves carry no
     * comment and the first commented move can sit many plies into the game — assigning comment k
     * to move k by document order silently shifted every label onto a position ~10 plies away
     * (often with the opposite side to move), turning the whole supervised signal into noise.
     */
    List<String> moves = new ArrayList<>();
    List<Double> evalPerMove = new ArrayList<>();
    Matcher tokenMatcher = MOVENTEXT_TOKEN_PATTERN.matcher(movetext);
    while (tokenMatcher.find()) {
      String token = tokenMatcher.group();
      if (token.startsWith("{")) {
        Matcher eval = EVAL_COMMENT_PATTERN.matcher(token);
        if (eval.find() && !moves.isEmpty()) {
          evalPerMove.set(moves.size() - 1, Double.parseDouble(eval.group(1)));
        }
        continue;
      }
      if (isResultToken(token)) continue;
      String san = token.replaceFirst("^\\d+\\.{1,3}", "");
      if (san.isEmpty()) continue;
      if (!looksLikeSan(san)) continue;
      moves.add(san);
      evalPerMove.add(Double.NaN);
    }

    if (moves.isEmpty()) return null;

    double[] evalCp = new double[moves.size()];
    for (int i = 0; i < evalCp.length; i++) evalCp[i] = evalPerMove.get(i);
    return new PgnGame(result, moves, evalCp, headers);
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
