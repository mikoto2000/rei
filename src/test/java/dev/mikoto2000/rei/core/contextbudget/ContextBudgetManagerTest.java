package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ContextBudgetManagerTest {

  @Test
  void contextSectionCanBeRepresented() {
    ContextSection section = new ContextSection("TASK_STATE", "goal: fix bug");
    assertEquals("TASK_STATE", section.name());
    assertEquals("goal: fix bug", section.content());
  }

  @Test
  void sizeCanBeEstimated() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    ContextSection section = new ContextSection("TASK_STATE", "goal: fix bug");
    assertTrue(manager.estimateTokens(section) > 0);
  }

  @Test
  void inputBudgetIsCalculated() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    assertEquals(118000, manager.inputBudget());
  }

  @Test
  void outputReserveIsSubtracted() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    assertEquals(118000, manager.inputBudget());
  }

  @Test
  void highPrioritySectionsAreIncludedFirst() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    ContextSection system = new ContextSection("SYSTEM", "system instructions");
    ContextSection user = new ContextSection("CURRENT_USER", "current user message");
    ContextSection task = new ContextSection("TASK_STATE", "goal: fix bug");
    ContextSection old = new ContextSection("OLD_HISTORY", "old history");

    AllocationResult result = manager.allocate(List.of(system, user, task, old));
    assertTrue(result.included().contains("SYSTEM"));
    assertTrue(result.included().contains("CURRENT_USER"));
    assertTrue(result.included().contains("TASK_STATE"));
  }

  @Test
  void allSectionsKeptWithinBudget() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    ContextSection system = new ContextSection("SYSTEM", "system");
    ContextSection user = new ContextSection("CURRENT_USER", "user");
    ContextSection task = new ContextSection("TASK_STATE", "task");

    AllocationResult result = manager.allocate(List.of(system, user, task));
    assertEquals(3, result.included().size());
  }

  @Test
  void overflowDropsLowPrioritySections() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection system = new ContextSection("SYSTEM", "system");
    ContextSection user = new ContextSection("CURRENT_USER", "user");
    ContextSection task = new ContextSection("TASK_STATE", "task");
    ContextSection old = new ContextSection("OLD_HISTORY", "old history " + "x".repeat(5000));

    AllocationResult result = manager.allocate(List.of(system, user, task, old));
    assertTrue(result.included().contains("SYSTEM"));
    assertTrue(result.included().contains("CURRENT_USER"));
    assertTrue(result.included().contains("TASK_STATE"));
    assertTrue(result.dropped().contains("OLD_HISTORY"));
  }

  @Test
  void systemAndCurrentUserAreNeverDropped() {
    ContextBudgetManager manager = new ContextBudgetManager(100, 100, 100);
    ContextSection system = new ContextSection("SYSTEM", "system");
    ContextSection user = new ContextSection("CURRENT_USER", "user");
    ContextSection task = new ContextSection("TASK_STATE", "task");

    AllocationResult result = manager.allocate(List.of(system, user, task));
    assertTrue(result.included().contains("SYSTEM"));
    assertTrue(result.included().contains("CURRENT_USER"));
  }
}
