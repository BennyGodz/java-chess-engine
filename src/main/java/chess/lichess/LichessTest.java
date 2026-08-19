package chess.lichess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class LichessTest {

    public static void main(String[] args) throws Exception {

        String token = System.getenv("LICHESS_TOKEN");

        if (token == null || token.isBlank()) {
            System.out.println("LICHESS_TOKEN is not set.");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://lichess.org/api/account"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status: " + response.statusCode());
        System.out.println(response.body());
    }
}