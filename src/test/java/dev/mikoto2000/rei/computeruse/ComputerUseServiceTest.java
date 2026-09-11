package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerUseServiceTest {
  @Test void cancellationFromActionStartedEventStillPreventsDispatch() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var input = new ArrayList<ComputerAction>();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> new ComputerAction.TypeText("text", ComputerAction.Risk.LOW), (a,s) -> input.add(a), a -> {},
        SafetyPolicy.lowRiskOnly(), cancelled::get, p -> { if (p.phase().equals("action_started")) cancelled.set(true); }, 20, 5);
    assertEquals(ComputerUseResult.Status.CANCELLED,service.run("goal").status());
    assertTrue(input.isEmpty());
  }

  @Test void cancellationFromCompletedEventPreventsStabilization() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var waits = new ArrayList<ComputerAction>();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> new ComputerAction.TypeText("text", ComputerAction.Risk.LOW), (a,s) -> {}, waits::add,
        SafetyPolicy.lowRiskOnly(), cancelled::get, p -> { if (p.phase().equals("action_completed")) cancelled.set(true); }, 20, 5);
    assertEquals(ComputerUseResult.Status.CANCELLED,service.run("goal").status());
    assertTrue(waits.isEmpty());
  }
  @Test void invalidDecisionNeverDispatchesAndLowConfidenceReobserves() {
    var calls = new ArrayList<ComputerAction>();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> new ComputerAction.Click(new ComputerAction.Target(500,0,"outside"), .9, ComputerAction.Risk.LOW),
        (a,s) -> calls.add(a), a -> {}, SafetyPolicy.lowRiskOnly(), () -> false, p -> {}, 2, 2);
    assertEquals(ComputerUseResult.Status.MODEL_ERROR, service.run("goal").status());
    assertTrue(calls.isEmpty());
    var progress = new ArrayList<ComputerProgress>();
    service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> o.step() == 1 ? new ComputerAction.Click(new ComputerAction.Target(10,10,"button"), .2, ComputerAction.Risk.LOW)
            : new ComputerAction.Done("Visible"),
        (a,s) -> calls.add(a), a -> {}, SafetyPolicy.lowRiskOnly(), () -> false, progress::add, 2, 2);
    assertEquals(ComputerUseResult.Status.DONE, service.run("goal").status());
    assertTrue(calls.isEmpty());
    assertTrue(progress.stream().anyMatch(p -> "decided".equals(p.phase()) && Double.valueOf(.2).equals(p.confidence())));
    assertEquals("DONE", progress.getLast().reason());
  }

  @Test void cancellationIsCheckedAtEveryBoundary() {
    for (String boundary : List.of("before", "capture", "model", "input", "wait")) {
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean(boundary.equals("before"));
      var sequence = new ArrayList<String>();
      var service = new ComputerUseService(() -> { sequence.add("capture"); if (boundary.equals("capture")) cancelled.set(true); return screen(); },
          o -> { sequence.add("model"); if (boundary.equals("model")) cancelled.set(true); return new ComputerAction.TypeText("x", ComputerAction.Risk.LOW); },
          (a,s) -> { sequence.add("input"); if (boundary.equals("input")) cancelled.set(true); },
          a -> { sequence.add("wait"); if (boundary.equals("wait")) cancelled.set(true); },
          SafetyPolicy.lowRiskOnly(), cancelled::get, p -> {}, 20, 2);
      assertEquals(ComputerUseResult.Status.CANCELLED, service.run("goal").status());
      assertEquals(List.of("capture", "model", "input", "wait").subList(0, List.of("before", "capture", "model", "input", "wait").indexOf(boundary)), sequence);
    }
  }
  @Test void cancellationAndSafetyStopBeforeDispatch() {
    var calls = new ArrayList<ComputerAction>();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    ComputerVisionModel model = o -> { cancelled.set(true); return new ComputerAction.TypeText("abc", ComputerAction.Risk.LOW); };
    var service = new ComputerUseService(ComputerUseServiceTest::screen, model, (a,s) -> calls.add(a),
        a -> {}, SafetyPolicy.lowRiskOnly(), cancelled::get, p -> {}, 20, 2);
    assertEquals(ComputerUseResult.Status.CANCELLED, service.run("goal").status());
    assertTrue(calls.isEmpty());
    cancelled.set(false);
    service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> new ComputerAction.PressKey("ENTER", ComputerAction.Risk.CONFIRM_REQUIRED),
        (a,s) -> calls.add(a), a -> {}, SafetyPolicy.lowRiskOnly(), cancelled::get, p -> {}, 20, 2);
    assertEquals(ComputerUseResult.Status.SAFETY_BLOCKED, service.run("goal").status());
    assertTrue(calls.isEmpty());
  }

  @Test void uncertainAndWaitReobserveWithoutInputAndHistoryIsBounded() {
    var seen = new ArrayList<ComputerObservation>();
    var calls = new ArrayList<ComputerAction>();
    var waits = new ArrayList<ComputerAction>();
    var service = new ComputerUseService(ComputerUseServiceTest::screen, o -> {
      seen.add(o); return o.step() % 2 == 0 ? new ComputerAction.Wait(10) : new ComputerAction.Uncertain("Loading");
    }, (a,s) -> calls.add(a), waits::add, SafetyPolicy.lowRiskOnly(), () -> false, p -> {}, 6, 2);
    assertEquals(ComputerUseResult.Status.MAX_STEPS, service.run("goal").status());
    assertTrue(calls.isEmpty());
    assertEquals(6, waits.size());
    assertEquals(2, seen.getLast().recentHistory().size());
  }

  @Test void distinguishesBoundaryFailures() {
    for (var expected : List.of(ComputerUseResult.Status.CAPTURE_ERROR, ComputerUseResult.Status.MODEL_ERROR,
        ComputerUseResult.Status.ACTION_ERROR, ComputerUseResult.Status.STABILIZATION_ERROR)) {
      var service = new ComputerUseService(() -> {
        if (expected == ComputerUseResult.Status.CAPTURE_ERROR) throw new Exception(); return screen();
      }, o -> { if (expected == ComputerUseResult.Status.MODEL_ERROR) throw new Exception();
        return new ComputerAction.TypeText("abc", ComputerAction.Risk.LOW);
      }, (a,s) -> { if (expected == ComputerUseResult.Status.ACTION_ERROR) throw new Exception(); },
          a -> { if (expected == ComputerUseResult.Status.STABILIZATION_ERROR) throw new Exception(); },
          SafetyPolicy.lowRiskOnly(), () -> false, p -> {}, 2, 2);
      assertEquals(expected, service.run("goal").status());
    }
  }

  @Test void failedDoesNotDispatch() {
    var service = new ComputerUseService(ComputerUseServiceTest::screen, o -> new ComputerAction.Failed("Cannot continue"),
        (a,s) -> fail(), a -> fail(), SafetyPolicy.lowRiskOnly(), () -> false, p -> {}, 2, 2);
    assertEquals(ComputerUseResult.Status.FAILED, service.run("goal").status());
  }
  static CapturedScreen screen() {
    return new CapturedScreen(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB),
        new Rectangle(0, 0, 320, 240));
  }

  @Test void observesAgainAfterEachActionAndOnlyDoneMeansSuccess() {
    var order = new ArrayList<String>();
    var observations = new ArrayList<ComputerObservation>();
    var actions = new ArrayDeque<ComputerAction>(List.of(
        new ComputerAction.Click(new ComputerAction.Target(100, 200, "editor"), .95, ComputerAction.Risk.LOW),
        new ComputerAction.TypeText("abc", ComputerAction.Risk.LOW), new ComputerAction.Done("Text visible")));
    var service = new ComputerUseService(() -> { order.add("capture"); return screen(); }, o -> {
      order.add("model"); observations.add(o); return actions.remove();
    }, (a, s) -> order.add(a.getClass().getSimpleName()), a -> order.add("wait"),
        SafetyPolicy.lowRiskOnly(), () -> false, progress -> {}, 20, 5);
    var result = service.run("Enter abc");
    assertEquals(ComputerUseResult.Status.DONE, result.status());
    assertEquals(List.of("capture", "model", "Click", "wait", "capture", "model", "TypeText", "wait", "capture", "model"), order);
    assertEquals(2, observations.getLast().recentHistory().size());
    assertEquals("Enter abc", observations.getLast().goal());
    assertEquals(3, observations.getLast().step());
  }
}
