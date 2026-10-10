package dev.mikoto2000.rei.doctor;

import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import dev.mikoto2000.rei.voice.*;
import dev.mikoto2000.rei.externalagent.*;
import dev.mikoto2000.rei.llm.LlmProperties;
import static org.junit.jupiter.api.Assertions.*;

class DoctorServiceTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
  VoiceModelManifest manifest() {
    return new VoiceModelManifest("fixture", List.of(new VoiceModelManifest.Asset("model.onnx", URI.create("https://example.invalid/model"), 1, "0".repeat(64), "MIT")));
  }
  DoctorService service(MockEnvironment env, VoiceProperties voice) {
    return new DoctorService(env, voice, new CodexProperties(), new ClaudeCodeProperties(), new LlmProperties(), clock, manifest());
  }
  VoiceProperties voice() {var voice = new VoiceProperties(); voice.setBundleDirectory(root.toString()); return voice;}
  DoctorResult result(List<DoctorResult> results, String id) {return results.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();}
  @Test void optionalAbsenceIsNotFailureAndPassiveRuntimeRemainsUnverified() {
    var results = service(new MockEnvironment(), voice()).passive(true);
    assertEquals(DoctorResult.Status.NOT_CONFIGURED, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.NOT_APPLICABLE, result(results, "external.claude").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "external.codex").status());
    assertEquals(clock.instant(), result(results, "runtime.java").observedAt());
    assertTrue(results.stream().allMatch(r -> !r.method().isBlank() && !r.evidence().isBlank() && !r.nextAction().isBlank()));
  }
  @Test void missingRequiredModelAndBadFileConfigurationDoNotSuppressOtherResults() {
    var voice = voice(); voice.setAutoStart(true);
    var env = new MockEnvironment().withProperty("rei.doctor.required-files", "bad" + (char)0 + "path");
    var results = service(env, voice).passive(true);
    assertEquals(DoctorResult.Status.ERROR, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.ERROR, result(results, "required.file.1").status());
    assertEquals(DoctorResult.Status.ERROR, result(results, "required.files").status());
    assertEquals(DoctorResult.Status.OK, result(results, "runtime.java").status());
  }
  @Test void presentFilesProveOnlyStaticPresenceAndNoIntegrityClaim() throws Exception {
    Files.write(root.resolve("model.onnx"), new byte[]{1});
    var results = service(new MockEnvironment(), voice()).passive(false);
    assertEquals(DoctorResult.Status.OK, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "voice.runtime").status());
    assertFalse(Files.exists(root.resolve("managed")));
  }
  @Test void secretsAndSourceNamesNeverReachReport() {
    var env = new MockEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("password=SOURCE-SECRET", Map.of("spring.ai.openai.base-url", "https://user:URL-SECRET@example.invalid/?token=QUERY-SECRET", "spring.ai.openai.api-key", "KEY-SECRET", "spring.ai.openai.chat.options.model", "MODEL-SECRET")));
    String rendered = DoctorService.render(service(env, voice()).passive(true));
    for (String secret : List.of("SOURCE-SECRET", "URL-SECRET", "QUERY-SECRET", "KEY-SECRET", "MODEL-SECRET", "https://", "example.invalid")) assertFalse(rendered.contains(secret), secret);
    assertTrue(rendered.contains("source #0"));
    assertTrue(rendered.contains("UNVERIFIED"));
  }
  @Test void winningPropertySourceIsIdentifiedWithoutDumpingValues() {
    var env = new MockEnvironment().withProperty("spring.ai.openai.base-url", "http://fallback.invalid");
    env.getPropertySources().addFirst(new MapPropertySource("systemProperties", Map.of("spring.ai.openai.base-url", "http://override.invalid")));
    var report = result(service(env, voice()).passive(true), "llm.endpoint");
    assertTrue(report.evidence().contains("system properties"));
    assertFalse(report.evidence().contains("fallback.invalid"));
    assertFalse(report.evidence().contains("override.invalid"));
  }
  @Test void networkSharePathsAreSkippedBeforeFilesystemAccess() {
    var voice = voice(); voice.setBundleDirectory("\\\\example.invalid\\share");
    var results = service(new MockEnvironment().withProperty("rei.doctor.required-files", "//example.invalid/share/file"), voice).passive(false);
    assertEquals(DoctorResult.Status.SKIPPED, result(results, "voice.bundle").status());
    assertEquals(DoctorResult.Status.SKIPPED, result(results, "required.file.1").status());
    assertEquals(DoctorResult.Status.UNVERIFIED, result(results, "required.files").status());
    assertTrue(result(results, "required.file.1").evidence().contains("not inspected"));
  }

  @Test void passiveAdviceDoesNotAdvertiseUnavailableActiveCommands() {
    var env = new MockEnvironment().withProperty("spring.ai.openai.base-url", "http://example.invalid").withProperty("spring.ai.openai.chat.options.model", "example");
    assertTrue(service(env, voice()).passive(false).stream().noneMatch(r -> r.nextAction().contains("/doctor --check")));
  }

  @Test void malformedEndpointIsAStaticConfigurationErrorWithUsefulEvidence() {
    var results = service(new MockEnvironment().withProperty("spring.ai.openai.base-url", "not a URL"), voice()).passive(false);
    var endpoint = result(results, "llm.endpoint"); assertEquals(DoctorResult.Status.ERROR, endpoint.status());
    assertTrue(endpoint.evidence().contains("HTTP(S)"));
  }
  @Test void disabledDoctorReturnsOnlySkippedMetadata() {
    var results = service(new MockEnvironment().withProperty("rei.doctor.enabled", "false"), voice()).passive(true);
    assertEquals(1, results.size()); assertEquals(DoctorResult.Status.SKIPPED, results.getFirst().status());
  }
}
