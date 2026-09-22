package dev.mikoto2000.rei.activity;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** PNG stays lossless; explicit RAM cache avoids ImageIO's default imageio*.tmp files. */
final class PngScreenshotEncoder {
  private PngScreenshotEncoder() {}

  static byte[] encode(BufferedImage image) throws IOException {
    var bytes = new ByteArrayOutputStream();
    write(image, bytes);
    return bytes.toByteArray();
  }

  static void write(BufferedImage image, OutputStream target) throws IOException {
    // Do not change ImageIO.setUseCache globally: other concurrent features own their cache policy.
    try (var output = new MemoryCacheImageOutputStream(target)) {
      if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
    }
  }
}
