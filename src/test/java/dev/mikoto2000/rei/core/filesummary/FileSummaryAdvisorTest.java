package dev.mikoto2000.rei.core.filesummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class FileSummaryAdvisorTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @TempDir
  Path tempDir;

  @Test
  void usableSummaryIsInjectedIntoUserMessage() throws Exception {
    Path file = tempDir.resolve("UserService.java");
    Files.writeString(file, "class UserService {}");
    String version = sha256(file);

    FileSummaryCache cache = new FileSummaryCache(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    cache.save(new FileSummary(file.toString(), version, "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));

    FileSummaryAdvisor advisor = new FileSummaryAdvisor(cache);
    UserMessage userMessage = UserMessage.builder().text("fix the bug").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("User の作成・更新を担当"));
    assertTrue(text.contains("fix the bug"));
  }

  @Test
  void staleSummaryIsNotInjected() throws Exception {
    Path file = tempDir.resolve("UserService.java");
    Files.writeString(file, "class UserService {}");

    FileSummaryCache cache = new FileSummaryCache(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    cache.save(new FileSummary(file.toString(), "stale-version", "古い要約",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));

    FileSummaryAdvisor advisor = new FileSummaryAdvisor(cache);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  private String sha256(Path path) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
