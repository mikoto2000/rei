package dev.mikoto2000.rei.shellautomation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Keeps bounded output while continuing to drain the pipe to avoid child-process deadlocks. */
public record ProcessOutputCapture(String text, boolean truncated, String readError) {
  public static final int LIMIT = 48 * 1024;

  public static ProcessOutputCapture read(InputStream stream, int limit) {
    var text = new StringBuilder();
    boolean truncated = false;
    String error = null;
    try (var reader =
        new InputStreamReader(
            stream,
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))) {
      char[] chunk = new char[4096];
      int count;
      while ((count = reader.read(chunk)) != -1) {
        int keep = Math.min(count, Math.max(0, limit - text.length()));
        text.append(chunk, 0, keep);
        truncated |= keep != count;
      }
    } catch (IOException failure) {
      error = failure.getClass().getSimpleName();
    }
    return new ProcessOutputCapture(text.toString(), truncated, error);
  }
}
