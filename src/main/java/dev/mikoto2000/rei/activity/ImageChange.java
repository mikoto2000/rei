package dev.mikoto2000.rei.activity;

import java.awt.image.BufferedImage;

/** Coarse RGB average differences tolerate tiny pixel changes without losing uniform-color changes. */
public final class ImageChange {
  private ImageChange() {}
  public static double[] fingerprint(BufferedImage image) {
    var scaled = new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
    var graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(image,0,0,32,32,null);
    } finally { graphics.dispose(); }
    var values = new double[32*32*3];
    for (int y=0;y<32;y++) for(int x=0;x<32;x++) {
      int rgb=scaled.getRGB(x,y), offset=(y*32+x)*3;
      for(int c=0;c<3;c++) values[offset+c]=(rgb>>(c*8))&255;
    }
    return values;
  }
  public static double distance(double[] a, double[] b) {
    if (a.length != b.length) return 1;
    double sum=0; for(int i=0;i<a.length;i++) sum+=Math.abs(a[i]-b[i]);
    return sum/(255*a.length);
  }
  public static double distance(BufferedImage a, BufferedImage b) { return distance(fingerprint(a),fingerprint(b)); }
}
