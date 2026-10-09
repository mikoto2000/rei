package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Preserves byte-level BPE fragments across sherpa 1.13.8's per-token UTF-8 cleanup.
 * Token IDs and model weights are unchanged; only the derived display vocabulary is encoded.
 * Remove together with decode when adopting an upstream runtime that joins bytes before cleanup.
 */
final class WhisperByteVocabulary implements AutoCloseable {
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private final Path path;
  private WhisperByteVocabulary(Path path) { this.path = path; }
  Path path() { return path; }
  static WhisperByteVocabulary create(Path source) throws IOException {
    byte[] input;
    try (var stream = Files.newInputStream(source)) { input = stream.readNBytes(MAX_BYTES + 1); }
    if (input.length > MAX_BYTES) throw new IllegalArgumentException("Vocabulary exceeds limit");
    String content = StandardCharsets.US_ASCII.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
    var ids = new HashSet<Integer>();
    var output = new StringBuilder();
    for (String line : content.lines().toList()) {
      int split = line.lastIndexOf(' ');
      if (split < 1 || split == line.length() - 1) throw new IllegalArgumentException("Malformed vocabulary");
      int id = Integer.parseInt(line.substring(split + 1));
      if (id < 0 || id > 100000 || !ids.add(id)) throw new IllegalArgumentException("Invalid token ID");
      String symbol = line.substring(0, split);
      byte[] raw = symbol.equals("=") ? new byte[0] : Base64.getDecoder().decode(symbol);
      output.append(raw.length == 0 ? "=" : Base64.getEncoder().encodeToString(encode(raw).getBytes(StandardCharsets.UTF_8)))
        .append(' ').append(id).append('\n');
    }
    if (ids.isEmpty()) throw new IllegalArgumentException("Empty vocabulary");
    Path derived = Files.createTempFile("rei-whisper-bytes-", ".txt");
    try { Files.writeString(derived, output, StandardCharsets.US_ASCII); }
    catch (IOException | RuntimeException e) { try { Files.deleteIfExists(derived); } catch (IOException cleanup) { e.addSuppressed(cleanup); } throw e; }
    return new WhisperByteVocabulary(derived);
  }
  static String encode(byte[] bytes) {
    var encoded = new StringBuilder(bytes.length);
    for (byte value : bytes) encoded.append((char)(0xe000 + (value & 255)));
    return encoded.toString();
  }
  static String decode(String encoded) throws CharacterCodingException {
    byte[] bytes = new byte[encoded.length()];
    for (int i = 0; i < bytes.length; i++) {
      char c = encoded.charAt(i);
      if (c < 0xe000 || c > 0xe0ff) throw new IllegalArgumentException("Unexpected Whisper byte encoding");
      bytes[i] = (byte)(c - 0xe000);
    }
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
  }
  public void close() throws IOException { Files.deleteIfExists(path); }
}
