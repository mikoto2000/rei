package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.externalagent.ClaudeCodeProperties;
import dev.mikoto2000.rei.externalagent.CodexProperties;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.voice.VoiceModelManifest;
import dev.mikoto2000.rei.voice.VoiceProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** 環境診断サービスの結果と状態の契約を検証するテスト。 */
class DoctorServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);

  /** {@code manifest} の条件を再現するための補助情報を返す。 */
  VoiceModelManifest manifest() {
    return new VoiceModelManifest(
        "fixture",
        List.of(
            new VoiceModelManifest.Asset(
                "model.onnx",
                URI.create("https://example.invalid/model"),
                1,
                "0".repeat(64),
                "MIT")));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  DoctorService service(MockEnvironment env, VoiceProperties voice) {
    return new DoctorService(
        env,
        voice,
        new CodexProperties(),
        new ClaudeCodeProperties(),
        new LlmProperties(),
        clock,
        manifest());
  }

  /** {@code voice} の条件を再現するための補助情報を返す。 */
  VoiceProperties voice() {
    var voice = new VoiceProperties();
    voice.setBundleDirectory(root.toString());
    return voice;
  }

  /** 検証で使用する結果を、指定された条件に合わせて用意する。 */
  DoctorResult result(List<DoctorResult> results, String id) {
    return results.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  /** 実行環境の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void optionalAbsenceIsNotFailureAndPassiveRuntimeRemainsUnverified() {
    var results = service(new MockEnvironment(), voice()).passive(true);
    assertEquals(DoctorResult.Status.NOT_CONFIGURED, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.NOT_APPLICABLE, result(results, "external.claude").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "external.codex").status());
    assertEquals(clock.instant(), result(results, "runtime.java").observedAt());
    assertTrue(
        results.stream()
            .allMatch(
                r ->
                    !r.method().isBlank() && !r.evidence().isBlank() && !r.nextAction().isBlank()));
  }

  /** 実行環境の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingRequiredModelAndBadFileConfigurationDoNotSuppressOtherResults() {
    var voice = voice();
    voice.setAutoStart(true);
    var env =
        new MockEnvironment().withProperty("rei.doctor.required-files", "bad" + (char) 0 + "path");
    var results = service(env, voice).passive(true);
    assertEquals(DoctorResult.Status.ERROR, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.ERROR, result(results, "required.file.1").status());
    assertEquals(DoctorResult.Status.ERROR, result(results, "required.files").status());
    assertEquals(DoctorResult.Status.OK, result(results, "runtime.java").status());
  }

  /** 実行環境の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void presentFilesProveOnlyStaticPresenceAndNoIntegrityClaim() throws Exception {
    Files.write(root.resolve("model.onnx"), new byte[] {1});
    var results = service(new MockEnvironment(), voice()).passive(false);
    assertEquals(DoctorResult.Status.OK, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "voice.runtime").status());
    assertFalse(Files.exists(root.resolve("managed")));
  }

  /** 実行環境の診断で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void secretsAndSourceNamesNeverReachReport() {
    var env = new MockEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "password=SOURCE-SECRET",
                Map.of(
                    "spring.ai.openai.base-url",
                    "https://user:URL-SECRET@example.invalid/?token=QUERY-SECRET",
                    "spring.ai.openai.api-key",
                    "KEY-SECRET",
                    "spring.ai.openai.chat.options.model",
                    "MODEL-SECRET")));
    String rendered = DoctorService.render(service(env, voice()).passive(true));
    for (String secret :
        List.of(
            "SOURCE-SECRET",
            "URL-SECRET",
            "QUERY-SECRET",
            "KEY-SECRET",
            "MODEL-SECRET",
            "https://",
            "example.invalid")) assertFalse(rendered.contains(secret), secret);
    assertTrue(rendered.contains("source #0"));
    assertTrue(rendered.contains("UNVERIFIED"));
  }

  /** 実行環境の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void winningPropertySourceIsIdentifiedWithoutDumpingValues() {
    var env =
        new MockEnvironment().withProperty("spring.ai.openai.base-url", "http://fallback.invalid");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "systemProperties",
                Map.of("spring.ai.openai.base-url", "http://override.invalid")));
    var report = result(service(env, voice()).passive(true), "llm.endpoint");
    assertTrue(report.evidence().contains("system properties"));
    assertFalse(report.evidence().contains("fallback.invalid"));
    assertFalse(report.evidence().contains("override.invalid"));
  }

  /** 実行環境の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void networkSharePathsAreSkippedBeforeFilesystemAccess() {
    var voice = voice();
    voice.setBundleDirectory("\\\\example.invalid\\share");
    var results =
        service(
                new MockEnvironment()
                    .withProperty("rei.doctor.required-files", "//example.invalid/share/file"),
                voice)
            .passive(false);
    assertEquals(DoctorResult.Status.SKIPPED, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.SKIPPED, result(results, "required.file.1").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "required.files").status());
    assertTrue(result(results, "required.file.1").evidence().contains("not inspected"));
  }

  /** 実行環境の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void passiveAdviceDoesNotAdvertiseUnavailableActiveCommands() {
    var env =
        new MockEnvironment()
            .withProperty("spring.ai.openai.base-url", "http://example.invalid")
            .withProperty("spring.ai.openai.chat.options.model", "example");
    assertTrue(
        service(env, voice()).passive(false).stream()
            .noneMatch(r -> r.nextAction().contains("/doctor --check")));
  }

  /** 実行環境の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedEndpointIsAStaticConfigurationErrorWithUsefulEvidence() {
    var results =
        service(
                new MockEnvironment().withProperty("spring.ai.openai.base-url", "not a URL"),
                voice())
            .passive(false);
    var endpoint = result(results, "llm.endpoint");
    assertEquals(DoctorResult.Status.ERROR, endpoint.status());
    assertTrue(endpoint.evidence().contains("HTTP(S)"));
  }

  /** 実行環境の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void disabledDoctorReturnsOnlySkippedMetadata() {
    var results =
        service(new MockEnvironment().withProperty("rei.doctor.enabled", "false"), voice())
            .passive(true);
    assertEquals(1, results.size());
    assertEquals(DoctorResult.Status.SKIPPED, results.getFirst().status());
  }
}
