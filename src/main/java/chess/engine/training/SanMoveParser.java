package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.pieces.Piece;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a SAN token (e.g. Nbd2, exd5, O-O, e8=Q) to the corresponding legal {@link Move} on a
 * {@link Board}. Used to replay PGN games for game-based training.
 */
public final class SanMoveParser {

  private SanMoveParser() {}

  /** Returns the unique legal move matching the SAN, or null when it cannot be resolved. */
  public static Move parse(Board board, String rawSan) {
    String san = normalize(rawSan);
    if (san.isEmpty()) return null;

    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

    if (san.equals("O-O")) return castlingMove(legalMoves, 6);
    if (san.equals("O-O-O")) return castlingMove(legalMoves, 2);

    boolean suppliedMate = san.endsWith("#");
    boolean suppliedCheck = san.endsWith("+");
    String core = san.replaceFirst("[+#]+$", "");

    char promotion = '\0';
    int promotionIndex = core.indexOf('=');
    if (promotionIndex >= 0) {
      if (promotionIndex != core.length() - 2) return null;
      promotion = Character.toUpperCase(core.charAt(core.length() - 1));
      core = core.substring(0, promotionIndex);
    } else if (core.length() >= 3) {
      char last = Character.toUpperCase(core.charAt(core.length() - 1));
      if ((last == 'Q' || last == 'R' || last == 'B' || last == 'N')
          && core.charAt(core.length() - 2) >= '1'
          && core.charAt(core.length() - 2) <= '8') {
        promotion = last;
        core = core.substring(0, core.length() - 1);
      }
    }

    if (!core.matches("[KQRBN]?[a-h1-8]{0,2}x?[a-h][1-8]")) return null;

    Position destination = parseSquare(core.substring(core.length() - 2));

    String prefix = core.substring(0, core.length() - 2);
    boolean captureSpecified = prefix.contains("x");
    prefix = prefix.replace("x", "");

    char pieceLetter = 'P';
    if (!prefix.isEmpty() && "KQRBN".indexOf(prefix.charAt(0)) >= 0) {
      pieceLetter = prefix.charAt(0);
      prefix = prefix.substring(1);
    }
    if (prefix.length() > 2) return null;
    String disambiguation = prefix;

    List<Move> matches = new ArrayList<>();
    for (Move move : legalMoves) {
      if (!move.getEnd().equals(destination)) continue;
      Piece piece = board.getPiece(move.getStart());
      if (piece == null || piece.getNotationSymbol() != pieceLetter) continue;
      boolean isCapture = move.isEnPassant() || board.getPiece(move.getEnd()) != null;
      if (captureSpecified && !isCapture) continue;
      if (move.isPromotion()) {
        if (promotion == '\0') continue;
        if (move.getPromotionPiece().getNotationSymbol() != promotion) continue;
      } else if (promotion != '\0') {
        continue;
      }
      if (!matchesDisambiguation(move, disambiguation)) continue;
      matches.add(move);
    }

    if (matches.size() != 1) return null;

    Move selected = matches.get(0);
    String actual = board.formatMove(selected);
    if (suppliedMate && !actual.endsWith("#")) return null;
    if (suppliedCheck && !(actual.endsWith("+") || actual.endsWith("#"))) return null;
    return selected;
  }

  private static String normalize(String input) {
    String s = input.trim();
    s = s.replace('0', 'O');
    s = s.replace('−', '-');
    s = s.replace('–', '-');
    s = s.replaceAll("\\s+", "");
    while (s.endsWith("!") || s.endsWith("?")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  private static Move castlingMove(List<Move> legalMoves, int endColumn) {
    for (Move move : legalMoves) {
      if (move.isCastling() && move.getEnd().getColumn() == endColumn) return move;
    }
    return null;
  }

  private static Position parseSquare(String square) {
    char file = Character.toLowerCase(square.charAt(0));
    char rank = square.charAt(1);
    return new Position(8 - (rank - '0'), file - 'a');
  }

  private static boolean matchesDisambiguation(Move move, String disambiguation) {
    if (disambiguation.isEmpty()) return true;
    char file = (char) ('a' + move.getStart().getColumn());
    char rank = (char) ('8' - move.getStart().getRow());
    if (disambiguation.length() == 1) {
      return disambiguation.charAt(0) == file || disambiguation.charAt(0) == rank;
    }
    return disambiguation.charAt(0) == file && disambiguation.charAt(1) == rank;
  }
}
