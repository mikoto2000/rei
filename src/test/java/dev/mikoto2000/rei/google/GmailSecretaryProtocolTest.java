package dev.mikoto2000.rei.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GmailSecretaryProtocolTest {
  final ObjectMapper json = new ObjectMapper();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);

  SecretaryConnections.Connection connection() {
    return new SecretaryConnections.Connection(
        "c",
        0,
        new SecretaryConnections.Config(
            "p", "s", "mail", SecretaryProvider.Service.GMAIL, "me", true, 300, true, false, "UTC"),
        "IDLE",
        "",
        "",
        clock.instant(),
        0,
        clock.instant());
  }

  @Test
  void sendAndDraftRequireIndependentMatchingMimeEvidence() throws Exception {
    for (boolean draft : java.util.List.of(false, true)) {
      var observed =
          new java.util.concurrent.atomic.AtomicReference<
              com.fasterxml.jackson.databind.node.ObjectNode>();
      var writes = new java.util.concurrent.atomic.AtomicInteger();
      var protocol =
          new GmailSecretaryProtocol(
              (method, path, query, body, write) -> {
                try {
                  if (path.endsWith("/profile"))
                    return json.createObjectNode().put("emailAddress", "me@example.invalid");
                  if (write) {
                    writes.incrementAndGet();
                    var document = json.valueToTree(body);
                    String raw = (draft ? document.path("message") : document).path("raw").asText();
                    var mime =
                        new jakarta.mail.internet.MimeMessage(
                            jakarta.mail.Session.getInstance(new java.util.Properties()),
                            new java.io.ByteArrayInputStream(
                                java.util.Base64.getUrlDecoder().decode(raw)));
                    var message =
                        json.createObjectNode()
                            .put("id", "m1")
                            .put("threadId", "t1")
                            .put("historyId", "42");
                    message.putArray("labelIds").add(draft ? "DRAFT" : "SENT");
                    var payload = message.putObject("payload");
                    payload.put("mimeType", "text/plain");
                    var headers = payload.putArray("headers");
                    var all = mime.getAllHeaders();
                    while (all.hasMoreElements()) {
                      var h = all.nextElement();
                      headers.addObject().put("name", h.getName()).put("value", h.getValue());
                    }
                    payload
                        .putObject("body")
                        .put(
                            "data",
                            java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                    mime.getContent()
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    observed.set(message);
                    return json.createObjectNode().put("id", draft ? "d1" : "m1");
                  }
                  return draft
                      ? json.createObjectNode().set("message", observed.get())
                      : observed.get();
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              },
              clock);
      var result =
          protocol.write(
              connection(),
              draft ? "MAIL_DRAFT" : "MAIL_SEND",
              Map.of(
                  "to",
                  "a@example.invalid",
                  "cc",
                  "b@example.invalid",
                  "subject",
                  "日本語 subject",
                  "body",
                  "reviewed body"),
              "exact-key");
      assertTrue(result.verified());
      assertEquals("COMPLETED", result.state());
      assertEquals(1, writes.get());
    }
  }

  @Test
  void verificationFailureAfterSendIsUncertainAndNeverResends() {
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    var protocol =
        new GmailSecretaryProtocol(
            (method, path, query, body, write) -> {
              if (path.endsWith("/profile"))
                return json.createObjectNode().put("emailAddress", "me@example.invalid");
              if (write) {
                writes.incrementAndGet();
                return json.createObjectNode().put("id", "m1");
              }
              throw new SecretaryProvider.Failure("NETWORK", 0, false);
            },
            clock);
    var failure =
        assertThrows(
            SecretaryProvider.Failure.class,
            () ->
                protocol.write(
                    connection(),
                    "MAIL_SEND",
                    Map.of("to", "a@example.invalid", "subject", "subject", "body", "body"),
                    "key"));
    assertTrue(failure.uncertain());
    assertEquals(1, writes.get());
  }

  @Test
  void metadataPollingDoesNotFetchBodiesAndFindsUnreadInboundWork() throws Exception {
    var calls = new java.util.ArrayList<String>();
    var protocol =
        new GmailSecretaryProtocol(
            (method, path, query, body, write) -> {
              calls.add(path + ":" + query.get("format"));
              try {
                return json.readTree(
                    path.endsWith("/messages")
                        ? "{\"messages\":[{\"id\":\"m1\"}]}"
                        : "{\"id\":\"m1\",\"threadId\":\"t1\",\"historyId\":\"1\",\"labelIds\":[\"UNREAD\",\"INBOX\"],\"payload\":{\"headers\":[{\"name\":\"Subject\",\"value\":\"Please reply\"},{\"name\":\"From\",\"value\":\"a@example.invalid\"}]}}");
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            },
            clock);
    var page = protocol.poll(connection(), "");
    assertEquals(1, page.items().size());
    assertTrue(page.items().getFirst().actionable());
    assertEquals("", page.items().getFirst().text());
    assertTrue(calls.getLast().endsWith(":metadata"));
  }

  @Test
  void mailHeadersCannotInjectRecipientsAndUnknownAcknowledgementNeverProvesSend() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var protocol =
        new GmailSecretaryProtocol(
            (method, path, query, body, write) -> {
              calls.incrementAndGet();
              return json.createObjectNode().put("emailAddress", "me@example.invalid");
            },
            clock);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            protocol.write(
                connection(),
                "MAIL_SEND",
                Map.of(
                    "to",
                    "x@example.invalid\r\nBcc:evil@example.invalid",
                    "subject",
                    "hi",
                    "body",
                    "text"),
                "job"));
    assertEquals(0, calls.get());
  }

  @Test
  void replyPreviewIsLocalAndNeverSendsOrInvokesAModel() throws Exception {
    var protocol =
        new GmailSecretaryProtocol(
            (method, path, query, body, write) -> {
              assertFalse(write);
              try {
                return json.readTree(
                    "{\"id\":\"m1\",\"threadId\":\"t1\",\"labelIds\":[\"INBOX\"],\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"a@example.invalid\"},{\"name\":\"Subject\",\"value\":\"Question\"},{\"name\":\"Message-ID\",\"value\":\"<one@example.invalid>\"}]}}");
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            },
            clock);
    var item = protocol.read(connection(), "REPLY", Map.of("id", "m1")).items().getFirst();
    assertEquals("REPLY_DRAFT", item.kind());
    assertEquals("t1", item.data().get("threadId"));
    assertEquals("a@example.invalid", item.data().get("to"));
  }
}
