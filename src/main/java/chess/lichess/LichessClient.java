package chess.lichess;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class LichessClient {

    private final String token;
    private final HttpClient httpClient;

    public LichessClient(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "LICHESS_TOKEN environment variable is missing."
            );
        }

        this.token = token;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void acceptChallenge(String challengeId)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://lichess.org/api/challenge/"
                                + challengeId
                                + "/accept"
                ))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Failed to accept challenge. HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }
    }

    public void makeMove(String gameId, String uciMove)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://lichess.org/api/bot/game/"
                                + gameId
                                + "/move/"
                                + uciMove
                ))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Failed to make move "
                            + uciMove
                            + ". HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }

        System.out.println(
                "Lichess accepted move: " + uciMove
        );
    }
}