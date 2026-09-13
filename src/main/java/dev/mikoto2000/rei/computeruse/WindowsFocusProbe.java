package dev.mikoto2000.rei.computeruse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Read-only UIA query, including bounded non-password editable field content; never changes focus. */
final class WindowsFocusProbe implements Supplier<String> {
  static final String UNKNOWN = "{\"status\":\"unknown\"}";
  private final BooleanSupplier cancelled;
  WindowsFocusProbe(BooleanSupplier cancelled) { this.cancelled = cancelled; }

  @Override public String get() {
    return query(null);
  }
  String atPoint(java.awt.Point point) { return query(java.util.Objects.requireNonNull(point)); }

  private String query(java.awt.Point point) {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return unknown("unsupported_os", "Windows UIA required");
    Process process = null;
    java.nio.file.Path output = null;
    java.nio.file.Path errors = null;
    try {
      if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
      String script;
      try (var in = getClass().getResourceAsStream("/computer-use/focus.ps1")) {
        script = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
      }
      if (point != null) script = "$reiPointX=" + point.x + "; $reiPointY=" + point.y + ";\n" + script;
      output = Files.createTempFile("rei-uia-focus-", ".json");
      errors = Files.createTempFile("rei-uia-focus-", ".stderr");
      String executable = java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
      process = new ProcessBuilder(executable,"-NoProfile","-NonInteractive","-WindowStyle","Hidden",
          "-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
          .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!process.waitFor(100,TimeUnit.MILLISECONDS)) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (System.nanoTime() >= deadline) return unknown("timeout", "UIA probe exceeded 5000 ms; probeProcessId=" + process.pid());
      }
      if (process.exitValue()!=0) {
        String stderr;
        try (var stream = Files.newInputStream(errors)) { stderr = new String(stream.readNBytes(1000),StandardCharsets.UTF_8); }
        return unknown("process_exit", "exitCode=" + process.exitValue() + "; " + stderr);
      }
      if (Files.size(output)>131072) return unknown("response_too_large", "UIA output exceeded 131072 bytes");
      return validate(Files.readString(output,StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException();
    } catch (java.util.concurrent.CancellationException e) { throw e;
    } catch (Exception e) { return unknown("probe_exception", e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (process!=null && process.isAlive()) process.destroyForcibly();
      if (output!=null) try { Files.deleteIfExists(output); } catch (Exception ignored) { }
      if (errors!=null) try { Files.deleteIfExists(errors); } catch (Exception ignored) { }
    }
  }

  static String validate(String text) throws Exception {
    com.fasterxml.jackson.databind.JsonNode node;
    try {
      node = new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text.strip());
    } catch (Exception e) { return unknown("invalid_json", e.getClass().getSimpleName()); }
    if (node!=null && "unknown".equals(node.path("status").asText())) return node.toString();
    if (node==null || !"ok".equals(node.path("status").asText())) return unknown("invalid_snapshot", "Missing status");
    for (String key : java.util.List.of("hasKeyboardFocus","enabled","password"))
      if (!node.path(key).isBoolean()) return unknown("invalid_snapshot",key);
    if (!node.has("editable") || !(node.get("editable").isNull() || node.get("editable").isBoolean())) return unknown("invalid_snapshot","editable");
    if (!node.path("name").isTextual() || !node.path("controlType").isTextual() || !node.path("bounds").isObject()) return unknown("invalid_snapshot","element identity or bounds");
    for (String key : java.util.List.of("x","y","width","height"))
      if (!node.path("bounds").path(key).isNumber() || !Double.isFinite(node.path("bounds").path(key).asDouble())) return unknown("invalid_snapshot","bounds." + key);
    if (node.path("probeOwnsFocus").asBoolean()) {
      var object = (com.fasterxml.jackson.databind.node.ObjectNode)node;
      object.put("status","unknown").put("reason","probe_owns_focus");
      object.putNull("editable");
      object.putNull("value").put("valueStatus","unavailable");
    }
    if (node.path("password").asBoolean())
      ((com.fasterxml.jackson.databind.node.ObjectNode)node).putNull("value").put("valueStatus","unavailable");
    return node.toString();
  }

  static String unknown(String reason, String detail) {
    var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    node.put("status","unknown").put("reason",reason)
        .put("detail",detail.substring(0,Math.min(1000,detail.length())));
    return node.toString();
  }
}
