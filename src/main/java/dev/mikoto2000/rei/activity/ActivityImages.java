package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Win32 window bounds are physical pixels. Windows AWT retains physical origins but scales display sizes. */
final class ActivityImages {
  private ActivityImages() {}
  static CapturedScreen foreground(CapturedScreen screen,ForegroundWindow window) {
    if(window==null || window.bounds()==null) return screen;
    var w=window.bounds();var rect=new Rectangle(w.x(),w.y(),w.width(),w.height());
    var selected=new ArrayList<DisplayCapture>();
    for(var display:screen.displays()) {
      var g=display.geometry();var b=physicalBounds(g);var intersection=b.intersection(rect);
      if(intersection.isEmpty()) continue;
      var image=display.image();
      int x=(int)((long)(intersection.x-b.x)*image.getWidth()/b.width);
      int y=(int)((long)(intersection.y-b.y)*image.getHeight()/b.height);
      int right=(int)Math.ceil((double)(intersection.x-b.x+intersection.width)*image.getWidth()/b.width);
      int bottom=(int)Math.ceil((double)(intersection.y-b.y+intersection.height)*image.getHeight()/b.height);
      var logicalCrop=new Rectangle(intersection.x,intersection.y,Math.max(1,(int)Math.round(intersection.width/g.scaleX())),Math.max(1,(int)Math.round(intersection.height/g.scaleY())));
      selected.add(new DisplayCapture(new ScreenGeometry(g.id(),logicalCrop,g.virtualBounds(),g.primary(),g.scaleX(),g.scaleY()),
          image.getSubimage(x,y,right-x,bottom-y)));
    }
    return selected.isEmpty()?screen:new CapturedScreen(selected);
  }
  static Map<String,double[]> backgroundFingerprints(CapturedScreen screen,ForegroundWindow window) {
    var result=new HashMap<String,double[]>();
    for(var display:screen.displays()) {
      var values=ImageChange.fingerprint(display.image());var b=physicalBounds(display.geometry());
      if(window!=null && window.bounds()!=null) {
        var w=window.bounds();var rect=new Rectangle(w.x(),w.y(),w.width(),w.height());
        // Mask entire sampling cells touching foreground, including the interpolation boundary.
        for(int y=0;y<32;y++) for(int x=0;x<32;x++) {
          var cell=new java.awt.geom.Rectangle2D.Double(b.x+(x-.5)*b.width/32.0,b.y+(y-.5)*b.height/32.0,b.width/16.0,b.height/16.0);
          if(cell.intersects(rect)) java.util.Arrays.fill(values,(y*32+x)*3,(y*32+x+1)*3,0);
        }
      }
      result.put(display.geometry().id(),values);
    }
    return Map.copyOf(result);
  }
  private static Rectangle physicalBounds(ScreenGeometry g) {
    var b=g.bounds();return new Rectangle(b.x,b.y,(int)Math.round(b.width*g.scaleX()),(int)Math.round(b.height*g.scaleY()));
  }
}
