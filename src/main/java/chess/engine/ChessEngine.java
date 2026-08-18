package chess.engine;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.engine.search.SearchEngine;

/** Public facade for the chess engine. */
public class ChessEngine {

    private final Evaluator evaluator;
    private final SearchEngine search;

    public ChessEngine() {
        evaluator = new Evaluator();
        search = new SearchEngine(evaluator);
    }

    public SearchEngine.SearchResult findBestMove(Board board, int depth, long timeLimitMillis) {
        return search.findBestMove(board, depth, timeLimitMillis);
    }

    public String evaluate(Board board) {
        return evaluator.explain(board);
    }

    public Move findBestMove(Board board) {
        return search.findBestMove(board, 9, 6_000).bestMove();
    }
}
