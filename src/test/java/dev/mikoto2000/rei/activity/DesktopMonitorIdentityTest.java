package dev.mikoto2000.rei.activity;
import java.util.*;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.computeruse.*;

class DesktopMonitorIdentityTest {
  @Test void interfaceIdsFollowBoundsAcrossAwtEnumerationChanges() {
    var left=new Rectangle(-32,0,32,32);var right=new Rectangle(0,0,32,32);var virtual=left.union(right);
    var image=ActivityChangeScopeTest.screen(10,20).image();
    var screen=new CapturedScreen(List.of(new DisplayCapture(new ScreenGeometry("index-0",right,virtual,true,1,1),image),new DisplayCapture(new ScreenGeometry("index-1",left,virtual,false,1,1),image)));
    var identities=List.of(new DesktopActivityObserver.MonitorIdentity("interface-left",new ActivityRecord.Bounds(-32,0,32,32)),new DesktopActivityObserver.MonitorIdentity("interface-right",new ActivityRecord.Bounds(0,0,32,32)));
    var mapped=WindowsDesktopActivityObserver.identify(screen,identities);
    assertEquals(List.of("interface-right","interface-left"),mapped.displays().stream().map(d->d.geometry().id()).toList());
    var reversed=WindowsDesktopActivityObserver.identify(new CapturedScreen(screen.displays().reversed()),identities);
    assertEquals(List.of("interface-left","interface-right"),reversed.displays().stream().map(d->d.geometry().id()).toList());
    assertSame(image,mapped.image());
  }
  @Test void missingOrAmbiguousMonitorMappingFailsClosed() {
    var screen=ActivityChangeScopeTest.screen(10,20);
    assertThrows(IllegalStateException.class,()->WindowsDesktopActivityObserver.identify(screen,List.of()));
    var same=new ActivityRecord.Bounds(0,0,32,32);
    assertThrows(IllegalStateException.class,()->WindowsDesktopActivityObserver.identify(screen,List.of(new DesktopActivityObserver.MonitorIdentity("a",same),new DesktopActivityObserver.MonitorIdentity("b",same))));
  }
}
