package dev.mikoto2000.rei.computeruse;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Locale;

/** Same-size visual ruler; never changes the origin or scale of a crop. */
final class CoordinateGrid {
  static BufferedImage annotate(BufferedImage source) {
    int width = source.getWidth(), height = source.getHeight();
    var result = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
    var g = result.createGraphics();
    try {
      g.drawImage(source,0,0,null);
      g.setFont(new Font(Font.MONOSPACED,Font.BOLD,Math.max(12,Math.min(width,height)/45)));
      for (int i = 0; i <= 10; i++) {
        int x = Math.min(width-1,i*width/10), y = Math.min(height-1,i*height/10);
        g.setColor(new Color(0,170,220,110));
        g.drawLine(x,0,x,height-1); g.drawLine(0,y,width-1,y);
        String label = String.format(Locale.ROOT,"%.1f",i/10.0);
        int textWidth = g.getFontMetrics().stringWidth(label), textHeight = g.getFontMetrics().getHeight();
        int labelX = Math.max(0,Math.min(width-textWidth-4,x+2));
        int labelY = Math.max(textHeight,Math.min(height-2,y+textHeight));
        g.setColor(Color.BLACK); g.fillRect(labelX,0,textWidth+4,textHeight); g.fillRect(0,labelY-textHeight,textWidth+4,textHeight);
        g.setColor(Color.CYAN); g.drawString(label,labelX+2,g.getFontMetrics().getAscent()); g.drawString(label,2,labelY-3);
      }
    } finally { g.dispose(); }
    return result;
  }
}
