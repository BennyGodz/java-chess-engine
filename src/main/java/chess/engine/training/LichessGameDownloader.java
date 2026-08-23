package chess.engine.training;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Downloads strong-player games to {@code games/lichess}. */
public final class LichessGameDownloader {

  static final String GAMES_DIR = "games" + File.separator + "lichess";

  private static final int DEFAULT_GAMES_PER_PLAYER = 500;
  private static final int MAX_RETRIES = 5;
  private static final long INITIAL_DELAY_MS = 2000;

  private static final List<String> PLAYERS =
      List.of(
          "Aqua_Blazing",
          "KnightCheckShadow",
          "Arkadiy_Khromaev",
          "JoshuaKimmich22",
          "VincentKeymer2004",
          "Zhigalko_Sergei",
          "wizard98",
          "Katarina0TP",
          "Federicov93",
          "Sigma_Tauri",
          "uSeRnAmEnOtAvAiL",
          "DONOTREDEEM",
          "dmitrij_IM",
          "LeonMendonca_YT",
          "FaustiOro",
          "Arseniii_Nesterov",
          "superchess2002",
          "barteljaap-jan",
          "abdurakhmanov_02",
          "severomorskij17",
          "Se7ens",
          "Ultra_D_Instinct",
          "Fritzi_2003",
          "pressive",
          "JustAHarmlessDuck",
          "rob188",
          "Papus1234",
          "Shprot86",
          "tacticthunder",
          "zvonokchess",
          "Kirill_Klyukin",
          "darkness_24",
          "Yakov25",
          "GutovAndrey",
          "Football_Fan",
          "StasSB",
          "worldwidewholesome",
          "wateenellende",
          "kiketf",
          "DraganSolak",
          "Fandorine96",
          "ABachmann",
          "Bacallao2019",
          "SHREKDAVID",
          "Cacheiro_08",
          "onepoundrook",
          "Guary1",
          "TrixR4Kidzzz",
          "newaccs",
          "Ilikeknightmost");

  private final int gamesPerPlayer;

  public LichessGameDownloader(int gamesPerPlayer) {
    this.gamesPerPlayer = Math.max(1, gamesPerPlayer);
  }

  public static void main(String[] args) {
    int gamesPerPlayer = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_GAMES_PER_PLAYER;

    try {
      new LichessGameDownloader(gamesPerPlayer).downloadAll();
    } catch (IOException e) {
      System.err.println("Download failed: " + e.getMessage());
      System.exit(1);
    }
  }

  public void downloadAll() throws IOException {
    File dir = new File(GAMES_DIR);
    Files.createDirectories(dir.toPath());

    List<String> players = new ArrayList<>(PLAYERS);
    Collections.shuffle(players, new Random());

    for (String username : players) {
      File target = new File(dir, username + ".pgn");
      if (target.isFile() && target.length() > 0) {
        System.out.println("Already downloaded: " + username);
        continue;
      }

      long delayMs = INITIAL_DELAY_MS;
      for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        try {
          Thread.sleep(delayMs);
          Files.writeString(target.toPath(), fetchPgn(username), StandardCharsets.UTF_8);
          System.out.println("Saved " + username + " -> " + target.getPath());
          break;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (IOException e) {
          delayMs *= 2;
          System.err.println(
              "Attempt "
                  + (attempt + 1)
                  + "/"
                  + MAX_RETRIES
                  + " failed for "
                  + username
                  + ": "
                  + e.getMessage());
          if (attempt == MAX_RETRIES - 1) System.err.println("Giving up on " + username);
        }
      }
    }
  }

  private String fetchPgn(String username) throws IOException {
    String url =
        "https://lichess.org/api/games/user/"
            + username
            + "?max="
            + gamesPerPlayer
            + "&pgnInJson=false&clocks=false&evals=false&opening=false";

    HttpURLConnection connection =
        (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Accept", "application/x-chess-pgn");
    connection.setRequestProperty("User-Agent", "BugaBot/1.0 chess training");
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(60000);

    int responseCode = connection.getResponseCode();
    if (responseCode != 200) {
      connection.disconnect();
      throw new IOException("Lichess returned HTTP " + responseCode);
    }

    String pgn;
    try (var input = connection.getInputStream()) {
      pgn = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }

    if (!pgn.contains("[Event ")) throw new IOException("No games returned");
    return pgn;
  }
}
