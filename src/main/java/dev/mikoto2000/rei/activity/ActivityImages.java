package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.awt.Rectangle;
import java.util.ArrayList;

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
  /** Fail closed when the enumeration is incomplete. Copies pixels before applying masks. */
  static CapturedScreen privateDesktop(CapturedScreen screen,DesktopActivityObserver.Metadata metadata,ActivityProperties p) {
    return privateDesktop(screen,metadata,p,true);
  }
  static CapturedScreen privateDesktop(CapturedScreen screen,DesktopActivityObserver.Metadata metadata,ActivityProperties p,boolean selectMonitors) {
    if(metadata==null || !metadata.complete() || metadata.visibleWindows()==null)return null;
    var policy=new CapturePolicy(p);var masks=new ArrayList<Rectangle>();
    for(var w:metadata.visibleWindows()) {
      if(w==null)return null;
      if(!w.visible() || w.minimized() || w.offScreen())continue;
      if(policy.excluded(w.window())) {
        if(w.window()==null || w.window().bounds()==null)return null;
        var b=w.window().bounds();if(b.width()<=0 || b.height()<=0)return null;
        // Include compositor shadows/borders adjacent to excluded windows.
        masks.add(new Rectangle(b.x()-16,b.y()-16,b.width()+32,b.height()+32));
      }
    }
    var selected=new ArrayList<DisplayCapture>();
    for(var display:screen.displays()) {
      var g=display.geometry();
      if(selectMonitors && !p.getDesktopContext().getMonitors().isEmpty() && !p.getDesktopContext().getMonitors().contains(g.id()))continue;
      var image=new java.awt.image.BufferedImage(display.image().getWidth(),display.image().getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
      var graphics=image.createGraphics();
      try {
        graphics.drawImage(display.image(),0,0,null);graphics.setColor(java.awt.Color.BLACK);
        var b=physicalBounds(g);
        for(var rect:masks)mask(graphics,rect,b,image);
        for(var mask:p.getDesktopContext().getMasks())if(g.id().equals(mask.monitor()))
          mask(graphics,new Rectangle(b.x+mask.x(),b.y+mask.y(),mask.width(),mask.height()),b,image);
      }finally{graphics.dispose();}
      selected.add(new DisplayCapture(g,image));
    }
    return selected.isEmpty()?null:new CapturedScreen(selected);
  }
  static CapturedScreen selectDesktop(CapturedScreen screen,ActivityProperties p) {
    var selected=screen.displays().stream().filter(d->p.getDesktopContext().getMonitors().isEmpty() || p.getDesktopContext().getMonitors().contains(d.geometry().id())).toList();
    return selected.isEmpty()?null:new CapturedScreen(selected);
  }
  private static void mask(java.awt.Graphics2D graphics,Rectangle rect,Rectangle b,java.awt.image.BufferedImage image) {
    var r=rect.intersection(b);if(r.isEmpty())return;
    int x=(int)Math.floor((double)(r.x-b.x)*image.getWidth()/b.width);
    int y=(int)Math.floor((double)(r.y-b.y)*image.getHeight()/b.height);
    int right=(int)Math.ceil((double)(r.x-b.x+r.width)*image.getWidth()/b.width);
    int bottom=(int)Math.ceil((double)(r.y-b.y+r.height)*image.getHeight()/b.height);
    graphics.fillRect(x,y,right-x,bottom-y);
  }
  static Rectangle physicalBounds(ScreenGeometry g) {
    var b=g.bounds();return new Rectangle(b.x,b.y,(int)Math.round(b.width*g.scaleX()),(int)Math.round(b.height*g.scaleY()));
  }
}
