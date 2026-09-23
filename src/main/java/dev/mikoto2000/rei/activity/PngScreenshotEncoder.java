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

  /** Only the outbound Vision copy is resized; capture geometry and optional evidence stay intact. */
  static byte[] encode(BufferedImage image,double scale) throws IOException {
    if(!Double.isFinite(scale) || scale<=0 || scale>1) throw new IllegalArgumentException("Invalid Vision image scale");
    if(scale==1) return encode(image);
    int width=Math.max(1,(int)Math.round(image.getWidth()*scale));
    int height=Math.max(1,(int)Math.round(image.getHeight()*scale));
    var resized=new BufferedImage(width,height,image.getColorModel().hasAlpha()?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
    var graphics=resized.createGraphics();
    try {
      graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(image,0,0,width,height,null);
    } finally {graphics.dispose();}
    return encode(resized);
  }

  static void write(BufferedImage image, OutputStream target) throws IOException {
    // Do not change ImageIO.setUseCache globally: other concurrent features own their cache policy.
    try (var output = new MemoryCacheImageOutputStream(target)) {
      if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
    }
  }
}
