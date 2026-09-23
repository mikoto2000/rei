package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityForegroundTest {
  @Test void cropsAcrossNegativeCoordinateDisplaysUsingPixelScale() {
    var left=new Rectangle(-200,0,100,100);var right=new Rectangle(0,0,100,100);
    var pixels=new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB);pixels.setRGB(150,20,0xff123456);
    var screen=new CapturedScreen(List.of(
        new DisplayCapture(new ScreenGeometry("left",left,left,false,2,2),pixels),
        new DisplayCapture(new ScreenGeometry("right",right,right,true,1,1),new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB))));
    var fg=new ForegroundWindow("Firefox",1,"X","1",new ActivityRecord.Bounds(-50,20,80,40));
    var selected=ActivityImages.foreground(screen,fg);
    assertEquals(2,selected.displays().size());
    assertEquals(new Rectangle(-50,20,25,20),selected.display("left").geometry().bounds());
    assertEquals(50,selected.display("left").image().getWidth());
    assertEquals(40,selected.display("left").image().getHeight());
    assertEquals(0xff123456,selected.display("left").image().getRGB(0,0));
    assertEquals(30,selected.display("right").image().getWidth());
    assertEquals(200,pixels.getWidth());
  }
  @Test void missingOrOffscreenBoundsFallBackToWholeScreen() {
    var screen=ActivityCaptureTest.screen(20);
    assertSame(screen,ActivityImages.foreground(screen,null));
    assertSame(screen,ActivityImages.foreground(screen,new ForegroundWindow("app",1,"","1")));
    assertSame(screen,ActivityImages.foreground(screen,new ForegroundWindow("app",1,"","1",new ActivityRecord.Bounds(999,999,10,10))));
  }
  @Test void win32BoundsUsePhysicalPixelsWhereAwtWidthsUseLogicalUnits() {
    var logical=new Rectangle(0,0,100,100);
    var pixels=new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB);pixels.setRGB(150,40,0xffabcdef);
    var screen=new CapturedScreen(List.of(new DisplayCapture(new ScreenGeometry("m",logical,logical,true,2,2),pixels)));
    var selected=ActivityImages.foreground(screen,new ForegroundWindow("app",1,"","1",new ActivityRecord.Bounds(150,40,40,80)));
    assertEquals(40,selected.image().getWidth());assertEquals(80,selected.image().getHeight());
    assertEquals(0xffabcdef,selected.image().getRGB(0,0));
  }
}
