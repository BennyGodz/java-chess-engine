package chess.engine.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One parsed PGN game, including optional self-play evaluation comments. */
public final class PgnGame {

  private static final Pattern EVAL_COMMENT_PATTERN =
      Pattern.compile("\\{\\s*ev\\s+(-?\\d+(?:\\.\\d+)?)\\s*}");
  private static final Pattern MOVENTEXT_TOKEN_PATTERN = Pattern.compile("\\{[^}]*\\}|\\S+");
  private final String result;
  private final List<String> sanMoves;
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

  public double[] getEvalCp() {
    return evalCp;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

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

    String movetext = pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");
    movetext = movetext.replaceAll(";[^\n]*", " ");
    movetext = movetext.replaceAll("\\$\\d+", " ");
    for (int i = 0; i < 5; i++) movetext = movetext.replaceAll("\\([^()]*\\)", " ");

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
      if (san.isEmpty() || !looksLikeSan(san)) continue;
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
    return switch (token) {
      case "1-0", "0-1", "1/2-1/2", "*" -> true;
      default -> false;
    };
  }

  private static boolean looksLikeSan(String token) {
    return token.matches(
        "^(O-O(-O)?|0-0(-0)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=?[QRBNqrbn])?)[+#?!]*$");
  }
}
