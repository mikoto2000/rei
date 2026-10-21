package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_INVALID;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.values;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * ChatGPTとの連携に必要な計画ログインを扱う。
 *
 * <p>The system browser is opened only by an explicit human login command.
 */
public final class PlanLogin {
  /** OAuth認証。 */
  private final PlanOAuth oauth;

  /** 認証用のURLをブラウザーで開く処理。 */
  private final Consumer<URI> browser;

  /** 指定された値や利用先を使って、PlanLoginを初期化する。 */
  public PlanLogin(PlanOAuth oauth, Consumer<URI> browser) {
    this.oauth = oauth;
    this.browser = browser;
  }

  /** ChatGPTとの連携に関するログインを処理する。 */
  public void login(String account) {
    HttpServer server = null;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      var callback = new CompletableFuture<Map<String, String>>();
      server.createContext(
          "/auth/callback",
          exchange -> {
            try {
              if (!exchange.getRequestMethod().equals("GET")
                  || !exchange.getRequestURI().getPath().equals("/auth/callback"))
                throw ChatGptException.of(AUTH_INVALID);
              var query = parse(exchange.getRequestURI().getRawQuery());
              callback.complete(query);
              byte[] reply =
                  "Return to rei to check the sign-in result.".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
              exchange.getResponseHeaders().set("Cache-Control", "no-store");
              exchange.sendResponseHeaders(200, reply.length);
              exchange.getResponseBody().write(reply);
            } catch (Exception e) {
              callback.completeExceptionally(ChatGptException.of(AUTH_INVALID));
              exchange.sendResponseHeaders(400, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      var attempt =
          oauth.begin(
              account,
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback"));
      browser.accept(attempt.authorization());
      oauth.complete(attempt, callback.get(600, TimeUnit.SECONDS));
    } catch (ChatGptException e) {
      throw e;
    } catch (TimeoutException e) {
      throw ChatGptException.of(TIMEOUT);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw ChatGptException.of(CANCELLED);
    } catch (Exception e) {
      throw ChatGptException.of(AUTH_INVALID);
    } finally {
      if (server != null) server.stop(0);
    }
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  static Map<String, String> parse(String raw) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (raw == null || raw.length() > 8192) throw ChatGptException.of(AUTH_INVALID);
    var values = new HashMap<String, String>();
    for (var part : raw.split("&")) {
      var pair = part.split("=", 2);
      String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
      if (values.putIfAbsent(key, value) != null) throw ChatGptException.of(AUTH_INVALID);
    }
    return values;
  }
}
