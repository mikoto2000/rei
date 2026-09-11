package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComputerDiagnosticsTest {
  @TempDir Path directory;
  @Test void storesOriginalImagesOverlayAndMappedCoordinatesWithoutChangingModelImage() throws Exception {
    var diagnostics = new ComputerDiagnostics(directory);
    var run = diagnostics.begin();
    var screen = MultiDisplayTest.desktop();
    diagnostics.observed(run,1,screen);
    var action = new ComputerAction.Click(new ComputerAction.Target("left",400,200,"private label"),.9,ComputerAction.Risk.LOW);
    diagnostics.action(run,1,screen,action,"decided");
    diagnostics.action(run,1,screen,action,"dispatched");
    var step = run.resolve("step-001");
    assertEquals(1600,ImageIO.read(step.resolve("display-2.png").toFile()).getWidth());
    var marker = ImageIO.read(step.resolve("target.png").toFile());
    assertNotEquals(screen.displays().get(1).image().getRGB(400,200),marker.getRGB(400,200));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(step.resolve("dispatched.json").toFile());
    assertEquals(-600,json.get("robotX").asInt());
    assertEquals(100,json.get("robotY").asInt());
    assertEquals("left",json.get("displayId").asText());
    assertFalse(Files.readString(step.resolve("dispatched.json")).contains("private label"));
  }
  @Test void disabledDoesNotCreateFiles() throws Exception {
    var diagnostics = new ComputerDiagnostics(null);
    var run = diagnostics.begin();
    diagnostics.observed(run,1,MultiDisplayTest.desktop());
    assertNull(run);
    try (var files = Files.list(directory)) { assertEquals(0,files.count()); }
  }
  @Test void serviceWritesObservedAndDispatchedArtifactsAndAnnouncesLocation() throws Exception {
    var events = new java.util.ArrayList<ComputerProgress>();
    var service = new ComputerUseService(MultiDisplayTest::desktop,
        o -> o.step() == 1 ? new ComputerAction.Click(new ComputerAction.Target("left",400,200,"editor"),.9,ComputerAction.Risk.LOW)
            : new ComputerAction.Done("visible"), (a,s) -> {}, a -> {}, SafetyPolicy.lowRiskOnly(), () -> false,
        events::add, 20,5,new ComputerDiagnostics(directory));
    assertEquals(ComputerUseResult.Status.DONE,service.run("goal").status());
    assertTrue(events.stream().anyMatch(e -> e.phase().equals("diagnostics") && e.reason().contains(directory.toString())));
    assertTrue(events.stream().anyMatch(e -> e.phase().equals("input_coordinates") && e.reason().contains("robot=(-600,100)")));
    try (var files = Files.walk(directory)) { assertEquals(1, files.filter(p -> p.getFileName().toString().equals("dispatched.json")).count()); }
  }
  @Test void artifactFailureDoesNotRetryOrPreventInput() throws Exception {
    Path file = Files.writeString(directory.resolve("file"),"occupied");
    var inputs = new java.util.ArrayList<ComputerAction>();
    var service = new ComputerUseService(MultiDisplayTest::desktop,
        o -> o.step() == 1 ? new ComputerAction.PressKey("TAB",ComputerAction.Risk.LOW) : new ComputerAction.Done("visible"),
        (a,s) -> inputs.add(a), a -> {}, SafetyPolicy.lowRiskOnly(), () -> false, p -> {},20,5,new ComputerDiagnostics(file));
    assertEquals(ComputerUseResult.Status.DONE,service.run("goal").status());
    assertEquals(1,inputs.size());
  }
}
