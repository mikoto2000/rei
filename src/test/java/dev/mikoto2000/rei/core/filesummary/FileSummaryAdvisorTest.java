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
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** ファイル要約対話の補助の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FileSummaryAdvisorTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void usableSummaryIsInjectedIntoUserMessage() throws Exception {
    Path file = tempDir.resolve("UserService.java");
    Files.writeString(file, "class UserService {}");
    String version = sha256(file);

    FileSummaryCache cache =
        new FileSummaryCache(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    cache.save(
        new FileSummary(
            file.toString(),
            version,
            "User の作成・更新を担当",
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

  /** ファイル内容の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleSummaryIsNotInjected() throws Exception {
    Path file = tempDir.resolve("UserService.java");
    Files.writeString(file, "class UserService {}");

    FileSummaryCache cache =
        new FileSummaryCache(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    cache.save(
        new FileSummary(
            file.toString(),
            "stale-version",
            "古い要約",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));

    FileSummaryAdvisor advisor = new FileSummaryAdvisor(cache);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  /** {@code sha256} の条件を再現するための補助情報を返す。 */
  private String sha256(Path path) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /** 検証条件を再現するためのNoop対話の補助Chain。 */
  private static final class NoopAdvisorChain implements AdvisorChain {
    /** 検証用に、ファイル内容の要約で参照する観測内容登録台帳を返す。 */
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
