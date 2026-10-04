package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.feed.FeedAuthenticationTest.properties;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DefaultFeedHttpFetcherTest {
  private HttpServer server;
  private HttpServer other;
  private String origin;
  private final String token = "a".repeat(43);

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    server.start();
    other.start();
  }

  @AfterEach
  void tearDown() { server.stop(0); other.stop(0); }

  @Test
  void sendsBearerOnlyForConfiguredFeedAndNeverForOtherPublicFeed() {
    AtomicReference<String> header = new AtomicReference<>();
    server.createContext("/feed.xml", exchange -> {
      header.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close();
    });
    server.createContext("/public.xml", exchange -> {
      header.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, -1); exchange.close();
    });
    var fetcher = authenticated();
    assertEquals(200, fetcher.fetch(URI.create(origin + "/feed.xml")).statusCode());
    assertEquals("Bearer " + token, header.get());
    fetcher.fetch(URI.create(origin + "/public.xml"));
    assertNull(header.get());
  }

  @Test
  void missingSecretMakesNoRequest() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(401, -1); exchange.close(); });
    var fetcher = new DefaultFeedHttpFetcher(new FeedAuthentication(properties(origin + "/feed.xml", origin, "REI_FEED_TOKEN"), name -> null, true));
    assertThrows(FeedFetchException.class, () -> fetcher.fetch(URI.create(origin + "/feed.xml")));
    assertEquals(0, calls.get());
  }

  @Test
  void authenticatedRedirectsAreNeverFollowedEvenToSameOrigin() {
    AtomicInteger forwarded = new AtomicInteger();
    other.createContext("/target", exchange -> { forwarded.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
    server.createContext("/target", exchange -> { forwarded.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
    AtomicReference<String> location = new AtomicReference<>("http://127.0.0.1:" + other.getAddress().getPort() + "/target");
    server.createContext("/feed.xml", exchange -> {
      exchange.getResponseHeaders().add("Location", location.get()); exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    for (String target : new String[] {location.get(), origin + "/target"}) {
      location.set(target);
      var error = assertThrows(FeedFetchException.class, () -> authenticated().fetch(URI.create(origin + "/feed.xml")));
      assertEquals(302, error.httpStatus());
      assertFalse(error.toString().contains(token));
    }
    assertEquals(0, forwarded.get());
  }

  @Test
  void publicRedirectBehaviorIsPreservedWithoutAuthorization() {
    AtomicReference<String> header = new AtomicReference<>();
    server.createContext("/public.xml", exchange -> { exchange.getResponseHeaders().add("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
    server.createContext("/target", exchange -> { header.set(exchange.getRequestHeaders().getFirst("Authorization")); exchange.sendResponseHeaders(200, -1); exchange.close(); });
    assertEquals(200, authenticated().fetch(URI.create(origin + "/public.xml")).statusCode());
    assertNull(header.get());
  }

  @Test
  void unauthorizedResponsesAreActionableButNeverEchoBody() {
    AtomicInteger status = new AtomicInteger(401);
    server.createContext("/feed.xml", exchange -> {
      byte[] body = ("echo " + token).getBytes(); exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    for (int code : new int[] {401, 403}) {
      status.set(code);
      var error = assertThrows(FeedFetchException.class, () -> authenticated().fetch(URI.create(origin + "/feed.xml")));
      assertEquals(code, error.httpStatus());
      assertTrue(error.getMessage().contains(Integer.toString(code)));
      assertFalse(error.toString().contains(token));
      assertNull(error.getCause());
    }
  }

  @Test
  void reflectedTokenInSuccessfulResponseIsNotReturnedForPersistence() {
    server.createContext("/feed.xml", exchange -> {
      byte[] body = ("<title>" + token + "</title>").getBytes();
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    var response = authenticated().fetch(URI.create(origin + "/feed.xml"));
    assertEquals(200, response.statusCode());
    assertFalse(response.body().contains(token));
  }

  private DefaultFeedHttpFetcher authenticated() {
    return new DefaultFeedHttpFetcher(new FeedAuthentication(properties(origin + "/feed.xml", origin, "REI_FEED_TOKEN"), name -> token, true));
  }
}
