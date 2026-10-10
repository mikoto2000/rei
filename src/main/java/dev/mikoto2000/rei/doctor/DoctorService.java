package dev.mikoto2000.rei.doctor;

import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import dev.mikoto2000.rei.voice.*;
import dev.mikoto2000.rei.externalagent.*;
import dev.mikoto2000.rei.llm.LlmProperties;
import static dev.mikoto2000.rei.doctor.DoctorResult.Status.*;

/** Metadata only: no model, network client, process executor, recorder or model manager dependency. */
@Service
public class DoctorService {
  private final Environment environment;
  private final VoiceProperties voice;
  private final CodexProperties codex;
  private final ClaudeCodeProperties claude;
  private final LlmProperties llm;
  private final Clock clock;
  private final VoiceModelManifest manifest;
  private dev.mikoto2000.rei.core.service.ModelHolderService currentModel;
  @Autowired public void setCurrentModel(dev.mikoto2000.rei.core.service.ModelHolderService holder) { currentModel = holder; }
  @Autowired public DoctorService(Environment environment, VoiceProperties voice, CodexProperties codex,
      ClaudeCodeProperties claude, LlmProperties llm) {
    this(environment, voice, codex, claude, llm, Clock.systemUTC(), VoiceModelManifest.pinned());
  }
  public DoctorService(Environment environment, VoiceProperties voice, CodexProperties codex,
      ClaudeCodeProperties claude, LlmProperties llm, Clock clock, VoiceModelManifest manifest) {
    this.environment=environment; this.voice=voice; this.codex=codex; this.claude=claude;
    this.llm=llm; this.clock=clock; this.manifest=manifest;
  }
  public List<DoctorResult> passive(boolean details) {
    var results = new ArrayList<DoctorResult>();
    if (!environment.getProperty("rei.doctor.enabled", Boolean.class, true))
      return List.of(result("doctor", SKIPPED, "configuration", "Doctor disabled", "Administrator configuration", "Enable rei.doctor.enabled when needed"));
    observe(results, "runtime.java", "JVM metadata", () -> result("runtime.java", Runtime.version().feature() >= 25 ? OK : ERROR,
        "JVM metadata", "Java feature version " + Runtime.version().feature(), "Rei requires JDK 25 or newer", "Use JDK 25 or newer"));
    observe(results, "runtime.os", "OS metadata", () -> result("runtime.os", OK, "OS metadata", osFamily(),
        "OS family only; device/native support is not tested", "Use explicit active checks for runtime support"));
    observe(results, "llm.endpoint", "effective configuration", () -> {
      String endpoint = endpoint();
      if (!present(endpoint)) return configured("llm.endpoint", NOT_CONFIGURED, "Endpoint not configured", details, endpointKey(), "Configure the existing OpenAI-compatible connection");
      URI uri;
      try { uri = URI.create(endpoint); } catch (IllegalArgumentException invalid) {
        return configured("llm.endpoint", ERROR, "Endpoint configuration is not an HTTP(S) URL with a host", details, endpointKey(), "Correct the endpoint setting locally without printing its value");
      }
      boolean valid = uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT)) && uri.getHost() != null;
      var status = !valid ? ERROR : uri.getUserInfo() != null || uri.getQuery() != null ? WARNING : UNVERIFIED;
      return configured("llm.endpoint", status, valid ? "Endpoint configured; connectivity and inference not tested" : "Endpoint configuration is not an HTTP(S) URL with a host", details, endpointKey(), "Connectivity requires a separate explicitly authorized active diagnosis");
    });
    observe(results, "llm.credentials", "effective configuration", () -> {
      var custom = llm.feature("chat");
      String key = custom != null && present(custom.getApiKey()) ? custom.getApiKey() : environment.getProperty("spring.ai.openai.api-key");
      return configured("llm.credentials", present(key) ? UNVERIFIED : NOT_CONFIGURED, present(key) ? "Credentials configured; validity not tested" : "Credentials not configured", details,
          custom != null && present(custom.getApiKey()) ? "rei.llm.features.chat.api-key" : "spring.ai.openai.api-key", "Use the existing API-key setting; never put credentials in URLs");
    });
    observe(results, "llm.model", "effective configuration", () -> {
      var custom = llm.feature("chat");
      String selected = custom != null && present(custom.getModel()) ? custom.getModel() : currentModel != null ? currentModel.get() : environment.getProperty("spring.ai.openai.chat.options.model");
      return result("llm.model", present(selected) ? UNVERIFIED : NOT_CONFIGURED, "effective configuration",
          present(selected) ? "Model selection configured; value omitted" : "Model selection not configured", "Model availability and remote GPU state not observed", "Inference requires a separate explicitly authorized active diagnosis");
    });
    observe(results, "external.codex", "effective configuration", () -> external("external.codex", codex.isEnabled(), codex.getCommand(), details, "rei.external-agents.codex.command"));
    observe(results, "external.claude", "effective configuration", () -> external("external.claude", claude.isEnabled(), claude.getCommand(), details, "rei.external-agents.claude.command"));
    observe(results, "voice.bundle", "filesystem metadata", () -> bundle(details));
    results.add(result("voice.runtime", UNVERIFIED, "passive; no inference", "Model loading, hash integrity and inference not tested", "Presence alone does not establish functionality", "Use /voice models info; verify integrity before enabling voice"));
    observe(results, "voice.microphone", "effective configuration", () -> configured("voice.microphone", present(voice.getDeviceId()) ? UNVERIFIED : NOT_CONFIGURED,
        present(voice.getDeviceId()) ? "Microphone selection configured; capture not tested" : "No explicit microphone selection", details, "rei.voice.device-id", "Select a device with /voice device; capture requires explicit active diagnosis"));
    for (String feature : List.of("rei.embedding.enabled", "rei.material-review.beginner.enabled", "rei.computer-use.enabled"))
      observe(results, feature, "effective configuration", () -> {
        boolean enabled = environment.getProperty(feature, Boolean.class, !feature.equals("rei.computer-use.enabled"));
        return configured(feature, enabled ? UNVERIFIED : NOT_APPLICABLE, enabled ? "Optional feature enabled; operation not tested" : "Optional feature disabled", details, feature, "Review the feature guide before enabling or testing it");
      });
    observe(results, "required.files", "effective configuration", () -> {
      String configured = environment.getProperty("rei.doctor.required-files", "");
      if (configured.isBlank()) return result("required.files", NOT_CONFIGURED, "effective configuration", "No extra required files configured", "Optional administrator checklist", "Set rei.doctor.required-files only when extra files are required");
      String[] files = configured.split(",", -1);
      for (int i=0; i<Math.min(files.length, 16); i++) {
        String id = "required.file." + (i+1); String file = files[i];
        observe(results, id, "filesystem metadata", () -> {
          if (remotePath(file.trim())) return result(id, SKIPPED, "configuration", "Network-share path not inspected", "Passive diagnosis does not probe shares", "Inspect this share separately");
          return result(id, Files.isRegularFile(Path.of(file.trim()), LinkOption.NOFOLLOW_LINKS) ? OK : ERROR,
              "filesystem metadata", "Required file presence checked; path and content omitted", "Missing, inaccessible or invalid required file", "Check this administrator-configured file locally");
        });
      }
      boolean failed = results.stream().anyMatch(r -> r.id().startsWith("required.file.") && r.status() == ERROR);
      boolean skipped = results.stream().anyMatch(r -> r.id().startsWith("required.file.") && r.status() == SKIPPED);
      return result("required.files", failed ? ERROR : files.length > 16 ? WARNING : skipped ? UNVERIFIED : OK, "effective configuration", "Required-file checklist evaluated with a 16-entry bound", "Missing/inaccessible files, skipped shares, or checklist bounds", "Review individual file results; split large checklists");
    });
    return List.copyOf(results);
  }
  private DoctorResult bundle(boolean details) throws Exception {
    if (!present(voice.getBundleDirectory())) return configured("voice.bundle", NOT_CONFIGURED, "Voice bundle directory not configured", details, "rei.voice.bundle-directory", "Configure or explicitly install the optional voice bundle");
    if (remotePath(voice.getBundleDirectory())) return result("voice.bundle", SKIPPED, "configuration", "Network-share path not inspected", "Passive diagnosis does not probe network shares", "Use a local model directory or inspect the share separately");
    Path root = Path.of(voice.getBundleDirectory());
    Path managed = VoiceModelManifest.assetPath(root, "managed/" + manifest.id());
    int best = 0;
    for (Path candidate : List.of(managed, root)) {
      int valid = 0;
      for (var asset : manifest.assets()) {
        Path file = VoiceModelManifest.assetPath(candidate, asset.path());
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == asset.bytes()) valid++;
      }
      best = Math.max(best, valid);
    }
    var status = best == manifest.assets().size() ? OK : voice.isAutoStart() ? ERROR : best == 0 ? NOT_CONFIGURED : WARNING;
    return configured("voice.bundle", status, "Expected file sizes present: " + best + "/" + manifest.assets().size() + "; hashes and inference unverified", details,
        "rei.voice.bundle-directory", "Use /voice models info and explicit installation; doctor does not download or repair");
  }
  private DoctorResult external(String id, boolean enabled, String command, boolean details, String property) {
    return configured(id, !enabled ? NOT_APPLICABLE : present(command) ? UNVERIFIED : NOT_CONFIGURED,
        !enabled ? "Optional external agent disabled" : "Command configuration checked; executable, authentication and startup not tested", details, property, "Use an explicit CLI active check when authorized");
  }
  public String endpoint() {
    var custom = llm.feature("chat");
    return custom != null && present(custom.getBaseUrl()) ? custom.getBaseUrl() : environment.getProperty("spring.ai.openai.base-url");
  }
  private String endpointKey() {return llm.feature("chat") != null && present(llm.feature("chat").getBaseUrl()) ? "rei.llm.features.chat.base-url" : "spring.ai.openai.base-url";}
  private DoctorResult configured(String id, DoctorResult.Status status, String evidence, boolean details, String key, String next) {
    return result(id, status, "effective configuration", evidence + (details ? "; " + key + ": " + source(key) : ""), "Static configuration only; live functionality not observed", next);
  }
  private String source(String key) {
    if (environment instanceof ConfigurableEnvironment configurable) {
      int index = 0;
      for (PropertySource<?> source : configurable.getPropertySources()) {
        if (source.getName().equals("configurationProperties")) continue;
        if (source.containsProperty(key)) {
          String name = source.getName();
          String kind = name.equals("systemProperties") ? "system properties" : name.equals("systemEnvironment") ? "system environment" : name.startsWith("Config resource") ? "application config" : "other property source";
          return "source #" + index + " (" + kind + ")";
        }
        index++;
      }
    }
    return "runtime configuration/default; no explicit property source";
  }
  private static String osFamily() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win") ? "Windows" : os.contains("mac") ? "macOS" : os.contains("linux") ? "Linux" : "other OS";
  }
  private static boolean remotePath(String value) {return value.startsWith("//") || value.startsWith("\\\\");}
  private static boolean present(String value) {return value != null && !value.isBlank();}
  private DoctorResult result(String id, DoctorResult.Status status, String method, String evidence, String causes, String next) {
    return new DoctorResult(id, status, clock.instant(), method, evidence, causes, next);
  }
  private void observe(List<DoctorResult> results, String id, String method, DoctorDiagnostic probe) {
    try { results.add(probe.inspect()); }
    catch (Exception error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      results.add(result(id, ERROR, method, "Diagnostic unavailable; exception details omitted", "Invalid or inaccessible configuration/files", "Inspect this check locally without exposing configuration secrets"));
    }
  }
  public static String render(List<DoctorResult> results) {
    var out = new StringBuilder("# Environment doctor (Passive: no network, process, inference or recording)\n");
    for (var result : results) out.append("\n").append(result.id()).append(": ").append(result.status()).append(" @ ").append(result.observedAt())
        .append("\nMethod: ").append(result.method()).append("\nEvidence: ").append(result.evidence())
        .append("\nCause candidates: ").append(result.causeCandidates()).append("\nNext: ").append(result.nextAction()).append('\n');
    return dev.mikoto2000.rei.event.CredentialRedactor.redact(out.toString());
  }
}
