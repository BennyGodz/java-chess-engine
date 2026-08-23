package chess.lichess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Lichess bot entry point: accepts challenges and streams one thread per running game. */
public class LichessBot {

  private final String token;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final LichessClient lichessClient;

  public LichessBot(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("LICHESS_TOKEN is missing.");
    }

    this.token = token;
    this.httpClient = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
    this.lichessClient = new LichessClient(token);
  }

  public void start() throws IOException, InterruptedException {
    System.out.println("================================");
    System.out.println("BugaBot Starting");
    System.out.println("================================");

    JsonNode account = getAccount();
    System.out.println("Logged in as: " + account.get("username").asText());
    System.out.println();
    System.out.println("Waiting for Lichess events...");

    streamEvents();
  }

  private JsonNode getAccount() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://lichess.org/api/account"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "Account request failed. HTTP " + response.statusCode() + ": " + response.body());
    }

    return mapper.readTree(response.body());
  }

  private void streamEvents() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://lichess.org/api/stream/event"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    if (response.statusCode() != 200) {
      throw new IOException("Event stream failed. HTTP " + response.statusCode());
    }

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        handleEvent(mapper.readTree(line));
      }
    }
  }

  private void handleEvent(JsonNode event) {
    String type = event.has("type") ? event.get("type").asText() : "";
    System.out.println();
    System.out.println("EVENT: " + type);

    switch (type) {
      case "challenge" -> handleChallenge(event);
      case "gameStart" -> handleGameStart(event);
      case "gameFinish" -> handleGameFinish(event);
      default -> System.out.println(event.toPrettyString());
    }
  }

  private void handleChallenge(JsonNode event) {
    JsonNode challenge = event.get("challenge");
    String challengeId = challenge.get("id").asText();
    String challenger = challenge.get("challenger").get("name").asText();

    System.out.println("Challenge from: " + challenger);
    System.out.println("Challenge ID: " + challengeId);

    try {
      lichessClient.acceptChallenge(challengeId);
      System.out.println("Challenge accepted!");
    } catch (Exception e) {
      System.err.println("Could not accept challenge:");
      e.printStackTrace();
    }
  }

  private void handleGameStart(JsonNode event) {
    JsonNode game = event.get("game");
    String gameId = game.get("id").asText();

    System.out.println();
    System.out.println("GAME STARTED!");
    System.out.println("Game ID: " + gameId);

    Thread gameThread =
        new Thread(
            () -> {
              try {
                new LichessGame(token, gameId).stream();
              } catch (Exception e) {
                System.err.println("Game stream error:");
                e.printStackTrace();
              }
            });

    gameThread.setName("LichessGame-" + gameId);
    gameThread.start();
  }

  private void handleGameFinish(JsonNode event) {
    System.out.println("GAME FINISHED");
    System.out.println(event.toPrettyString());
  }

  public static void main(String[] args) {
    String token = System.getenv("LICHESS_TOKEN");

    if (token == null || token.isBlank()) {
      System.err.println("LICHESS_TOKEN environment variable is not set.");
      return;
    }

    try {
      new LichessBot(token).start();
    } catch (Exception e) {
      System.err.println("BugaBot crashed:");
      e.printStackTrace();
    }
  }
}
