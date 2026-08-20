package chess.engine.training;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Handles communication with Stockfish through the UCI protocol.
 *
 * Stockfish must be installed separately.
 */
public class Stockfish implements AutoCloseable {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public Stockfish(String stockfishPath) throws IOException {

        ProcessBuilder processBuilder =
                new ProcessBuilder(stockfishPath);

        processBuilder.redirectErrorStream(true);

        process = processBuilder.start();

        writer = new BufferedWriter(
                new OutputStreamWriter(
                        process.getOutputStream(),
                        StandardCharsets.UTF_8
                )
        );

        reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                )
        );

        sendCommand("uci");
        waitFor("uciok");

        sendCommand("isready");
        waitFor("readyok");
    }

    /**
     * Evaluate a position using Stockfish.
     *
     * Returns evaluation from White's perspective.
     *
     * Example:
     *
     * cp 35  ->  0.35
     * cp -120 -> -1.20
     */
    public double evaluate(String fen, int depth) throws IOException {

        sendCommand("position fen " + fen);
        sendCommand("go depth " + depth);

        double evaluation = 0.0;

        String line;

        while ((line = reader.readLine()) != null) {

            if (line.startsWith("info ")
                    && line.contains(" score ")) {

                Double score = parseScore(line);

                if (score != null) {
                    evaluation = score;
                }
            }

            if (line.startsWith("bestmove")) {
                break;
            }
        }

        return evaluation;
    }

    /**
     * Parse Stockfish's score.
     */
    private Double parseScore(String line) {

        int scoreIndex = line.indexOf(" score ");

        if (scoreIndex == -1) {
            return null;
        }

        String remaining =
                line.substring(scoreIndex + 7).trim();

        String[] parts =
                remaining.split("\\s+");

        if (parts.length < 2) {
            return null;
        }

        String type = parts[0];
        String value = parts[1];

        try {

            if (type.equals("cp")) {

                int centipawns =
                        Integer.parseInt(value);

                return centipawns / 100.0;
            }

            /*
             * Convert mate scores into a large evaluation.
             *
             * Positive mate means White is winning.
             * Negative mate means Black is winning.
             */
            if (type.equals("mate")) {

                int mate =
                        Integer.parseInt(value);

                if (mate > 0) {
                    return 10.0;
                }

                if (mate < 0) {
                    return -10.0;
                }

                return 0.0;
            }

        } catch (NumberFormatException ignored) {
        }

        return null;
    }

    private void sendCommand(String command)
            throws IOException {

        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    private void waitFor(String expected)
            throws IOException {

        String line;

        while ((line = reader.readLine()) != null) {

            if (line.trim().equals(expected)) {
                return;
            }
        }

        throw new IOException(
                "Stockfish did not respond with " + expected
        );
    }

    public void stop() throws IOException {

        sendCommand("stop");
    }

    @Override
    public void close() throws IOException {

        try {
            sendCommand("quit");
        } finally {

            writer.close();
            reader.close();
            process.destroy();
        }
    }
}