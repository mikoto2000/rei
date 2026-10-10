package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopContextSafetyTest {
  @Test void supplementalCandidatesNeverBecomePrimaryOrChangeTime() {
    var record=TemporalActivityInferenceTest.record("a",TemporalActivityInferenceTest.AT,"tests");
    var video=new ActivityRecord.Activity("monitor-2","media","Chrome","youtube","放置動画","");
    var result=new ActivityExtractor.Result(new ActivityRecord.Inference("背景",List.of(video)),.9);
    var merged=ActivityBackgroundMerge.merge(record,result,.5);
    assertEquals(record.inference(),merged.inference());
    assertEquals(record.durationEstimate(),merged.durationEstimate());
    assertEquals(List.of(video),merged.detection().visionDiagnostics().background().context().candidates());
    assertEquals(record.capturedAt(),merged.detection().visionDiagnostics().background().context().observedAt());
  }
  @Test void masksExcludedBackgroundAndSelectedAreasWithoutMutatingOriginal() {
    var p=new ActivityProperties();p.getDesktopContext().setMonitors(List.of("primary"));
    var screen=ActivityChangeScopeTest.screen(20,20);
    var fg=new ForegroundWindow("IDE",1,"work","fg",new ActivityRecord.Bounds(0,0,5,5));
    var secret=new ForegroundWindow("KeePassXC",2,"private","secret",new ActivityRecord.Bounds(5,5,5,5));
    var metadata=new DesktopActivityObserver.Metadata(fg,List.of(new ActivityEvidence.VisibleWindow(secret,true,false,false,"primary")),true);
    var safe=ActivityImages.privateDesktop(screen,metadata,p);
    assertNotNull(safe);
    assertEquals(0,safe.displays().getFirst().image().getRGB(6,6)&0xffffff);
    assertNotSame(screen.displays().getFirst().image(),safe.displays().getFirst().image());
    assertNull(ActivityImages.privateDesktop(screen,new DesktopActivityObserver.Metadata(fg,List.of()),p));
    p.getDesktopContext().setMonitors(List.of("disconnected"));assertNull(ActivityImages.privateDesktop(screen,metadata,p));
  }
  @Test void backgroundPolicyHasCooldownHourlyCapAndIdleSuppression() {
    var p=new ActivityProperties();p.getDetection().setBackgroundFullScreenEnabled(true);
    var gate=new DesktopAnalysisPolicy(p);
    var at=TemporalActivityInferenceTest.AT;
    assertFalse(gate.due(at,true,"project-a"));
    assertTrue(gate.due(at,false,"project-a"));gate.started(at);
    assertFalse(gate.due(at.plusSeconds(59),false,"project-b"));
    assertTrue(gate.due(at.plusSeconds(60),false,"project-b"));gate.started(at.plusSeconds(60));
    for(int i=2;i<6;i++){assertTrue(gate.due(at.plusSeconds(i*300),false,"p"+i));gate.started(at.plusSeconds(i*300));}
    assertFalse(gate.due(at.plusSeconds(1800),false,"another"));
    assertTrue(gate.due(at.plusSeconds(3600),false,"another"));
  }
  @Test void masksPhysicalPixelsOnNegativeOriginAndKeepsMonitorIdentity() {
    var bounds=new java.awt.Rectangle(-40,0,20,20);
    var image=new java.awt.image.BufferedImage(40,40,java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,40,40);graphics.dispose();
    var screen=new dev.mikoto2000.rei.computeruse.CapturedScreen(List.of(new dev.mikoto2000.rei.computeruse.DisplayCapture(
        new dev.mikoto2000.rei.computeruse.ScreenGeometry("device-a",bounds,bounds,true,2,2),image)));
    var fg=new ForegroundWindow("IDE",1,"work","fg",new ActivityRecord.Bounds(-40,0,10,10));
    var p=new ActivityProperties();p.getDesktopContext().setMasks(List.of(new ActivityProperties.Mask("device-a",0,0,5,5)));
    var safe=ActivityImages.privateDesktop(screen,new DesktopActivityObserver.Metadata(fg,List.of(),true),p);
    assertEquals("device-a",safe.displays().getFirst().geometry().id());
    assertEquals(0,safe.image().getRGB(1,1)&0xffffff);assertEquals(0xffffff,safe.image().getRGB(10,10)&0xffffff);
    assertEquals(0xffffff,image.getRGB(1,1)&0xffffff);
  }
  @Test void unknownExcludedBoundsFailClosedAndInvalidSettingsAreRejected() {
    var p=new ActivityProperties();var fg=new ForegroundWindow("IDE",1,"work","fg");
    var hidden=new ForegroundWindow("KeePassXC",2,"private","private");
    var metadata=new DesktopActivityObserver.Metadata(fg,List.of(new ActivityEvidence.VisibleWindow(hidden,true,false,false,"primary")),true);
    assertNull(ActivityImages.privateDesktop(ActivityChangeScopeTest.screen(20,20),metadata,p));
    p.getDesktopContext().setMasks(List.of(new ActivityProperties.Mask("primary",0,0,0,10)));
    assertThrows(IllegalArgumentException.class,p::validate);
  }
  @Test void actualDesktopStartsRespectRollingLimitDespiteQueueDelays() {
    var p=new ActivityProperties();p.getDetection().setBackgroundFullScreenEnabled(true);var gate=new DesktopAnalysisPolicy(p);
    var at=TemporalActivityInferenceTest.AT;
    for(int i=0;i<6;i++){assertTrue(gate.executionAllowed(at.plusSeconds(i*60)));gate.executed(at.plusSeconds(i*60));}
    assertFalse(gate.executionAllowed(at.plusSeconds(3599)));
    assertTrue(gate.executionAllowed(at.plusSeconds(3600)));
    gate.executed(at.plusSeconds(3600));assertFalse(gate.executionAllowed(at.plusSeconds(3601)));
  }
}
