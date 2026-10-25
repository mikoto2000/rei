package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretarySlackTest {
  final ObjectMapper json = new ObjectMapper();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);

  SlackProperties properties() {
    var p = new SlackProperties();
    p.setAllowedWorkspaceIds(Set.of("T1"));
    p.setAllowedUserIds(Set.of("U1"));
    p.setAllowedChannelIds(Set.of("C1"));
    p.setChannelProjects(Map.of("C1", "p"));
    return p;
  }

  SecretaryConnections.Connection connection() {
    return new SecretaryConnections.Connection(
        "c",
        0,
        new SecretaryConnections.Config(
            "p", "s", "slack", SecretaryProvider.Service.SLACK, "C1", true, 60, true, false, "UTC"),
        "IDLE",
        "",
        "",
        clock.instant(),
        0,
        clock.instant());
  }

  @Test
  void pollingReusesHumanAdmissionAndRejectsBotSelfAndMachineMessages() {
    try (var source =
        new SecretarySlackProvider(
            true, properties(), Map.of("SLACK_BOT_TOKEN", "fixture"), clock) {
          @Override
          protected com.fasterxml.jackson.databind.JsonNode api(
              String method, Map<String, String> query, boolean user) {
            try {
              return json.readTree(
                  method.equals("auth.test")
                      ? "{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UBOT\"}"
                      : "{\"ok\":true,\"messages\":[{\"ts\":\"1.000001\",\"user\":\"U1\",\"text\":\"<@UBOT> please check\"},{\"ts\":\"1.000002\",\"user\":\"U1\",\"bot_id\":\"B2\",\"text\":\"<@UBOT> auto ack\"},{\"ts\":\"1.000003\",\"user\":\"UBOT\",\"text\":\"<@UBOT> self\"},{\"ts\":\"1.000004\",\"user\":\"U1\",\"subtype\":\"bot_message\",\"text\":\"<@UBOT> machine\"}]}");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }
        }) {
      var page = source.poll(connection(), "");
      assertEquals(1, page.items().size());
      assertTrue(page.items().getFirst().actionable());
    }
  }

  @Test
  void publicChannelThreadsRequireTheSeparatelyConfiguredUserReadToken() {
    try (var source =
        new SecretarySlackProvider(
            true, properties(), Map.of("SLACK_BOT_TOKEN", "fixture"), clock) {
          @Override
          protected com.fasterxml.jackson.databind.JsonNode api(
              String method, Map<String, String> query, boolean user) {
            try {
              return json.readTree("{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UBOT\"}");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }
        }) {
      var failure =
          assertThrows(
              SecretaryProvider.Failure.class,
              () -> source.read(connection(), "THREAD", Map.of("ts", "1.000001")));
      assertEquals("USER_TOKEN_REQUIRED", failure.code());
    }
  }

  @Test
  void progressDeliveryUsesExistingTransportAndDoesNotPingEmbeddedMentions() {
    var sent = new java.util.concurrent.atomic.AtomicReference<SlackStore.Outgoing>();
    try (var source =
        new SecretarySlackProvider(
            true, properties(), Map.of("SLACK_BOT_TOKEN", "fixture"), clock) {
          @Override
          protected com.fasterxml.jackson.databind.JsonNode api(
              String method, Map<String, String> query, boolean user) {
            try {
              return json.readTree(
                  method.equals("auth.test")
                      ? "{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UBOT\"}"
                      : "{\"ok\":true,\"messages\":[{\"ts\":\"2.000001\",\"user\":\"UBOT\",\"text\":\"status &lt;@U2&gt;\"}]}");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }

          @Override
          protected SlackTransport transport(String team) {
            return row -> {
              sent.set(row);
              return SlackTransport.Delivery.sent("2.000001");
            };
          }
        }) {
      var outcome =
          source.write(connection(), "SLACK_POST", Map.of("body", "status <@U2>"), "action");
      assertTrue(outcome.verified());
      assertEquals("status &lt;@U2&gt;", sent.get().text());
      assertTrue(sent.get().id().startsWith("secretary:"));
    }
  }
}
