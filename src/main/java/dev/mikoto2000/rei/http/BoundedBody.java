package dev.mikoto2000.rei.http;

import java.io.*;
import java.util.Locale;
import java.util.zip.InflaterInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Wire limits are checked before each chunk copy; decompression never builds an unbounded result. */
public final class BoundedBody {
  private BoundedBody() {}
  public static void checkWireSize(long current, long incoming, long maximum) {
    if (current < 0 || incoming < 0 || maximum <= 0 || current > maximum || incoming > maximum - current)
      throw new HttpFetchException(HttpFetchException.Code.WIRE_LIMIT);
  }
  public static byte[] decode(byte[] body, String encoding, int maxWire, int maxDecoded, Runnable check) {
    check.run();
    checkWireSize(0, body.length, maxWire);
    if (maxDecoded <= 0) throw new IllegalArgumentException("maxDecoded must be positive");
    String coding = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
    if (coding.isEmpty() || coding.equals("identity")) {
      if (body.length > maxDecoded) throw new HttpFetchException(HttpFetchException.Code.DECODED_LIMIT);
      check.run();
      return body.clone();
    }
    try (var input = decodedStream(body, coding, check); var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[Math.min(8192, maxDecoded)];
      int count;
      while ((count = input.read(buffer)) != -1) {
        check.run();
        if (count > maxDecoded - output.size()) throw new HttpFetchException(HttpFetchException.Code.DECODED_LIMIT);
        output.write(buffer, 0, count);
      }
      check.run();
      return output.toByteArray();
    } catch (IOException error) { throw new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR, error); }
  }
  private static InputStream decodedStream(byte[] body, String coding, Runnable check) throws IOException {
    var raw = new ByteArrayInputStream(body);
    return switch (coding) {
      case "gzip", "x-gzip" -> GzipCompressorInputStream.builder().setInputStream(raw)
          .setDecompressConcatenated(true).setOnMemberStart(member -> check.run()).get();
      case "deflate" -> new InflaterInputStream(raw);
      default -> throw new HttpFetchException(HttpFetchException.Code.UNSUPPORTED_ENCODING);
    };
  }
}
