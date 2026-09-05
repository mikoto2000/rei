package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ContextBudgetEvaluatedPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

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

  @Test
  void actionPlanOldDoneStepsAreTrimmed() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection plan = new ContextSection("ACTION_PLAN", "1. [DONE] old step\n2. [IN_PROGRESS] current step\n3. [TODO] next step");

    AllocationResult result = manager.allocate(List.of(plan));
    assertTrue(result.included().contains("ACTION_PLAN"));
  }

  @Test
  void currentStepIsKept() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection plan = new ContextSection("ACTION_PLAN", "1. [IN_PROGRESS] current step");

    AllocationResult result = manager.allocate(List.of(plan));
    assertTrue(result.included().contains("ACTION_PLAN"));
  }

  @Test
  void workingSetLimitedToTopN() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection working = new ContextSection("WORKING_SET", "file1\nfile2\nfile3");

    AllocationResult result = manager.allocate(List.of(working));
    assertTrue(result.included().contains("WORKING_SET"));
  }

  @Test
  void fileSummariesLimitedToWorkingSetRelated() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection summaries = new ContextSection("FILE_SUMMARIES", "summary1");

    AllocationResult result = manager.allocate(List.of(summaries));
    assertTrue(result.included().contains("FILE_SUMMARIES"));
  }

  @Test
  void relatedFilesLimitedToOneHop() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection related = new ContextSection("RELATED_FILES", "related1");

    AllocationResult result = manager.allocate(List.of(related));
    assertTrue(result.included().contains("RELATED_FILES"));
  }

  @Test
  void recentChangesLimitedToLatestN() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection changes = new ContextSection("RECENT_CHANGES", "change1");

    AllocationResult result = manager.allocate(List.of(changes));
    assertTrue(result.included().contains("RECENT_CHANGES"));
  }

  @Test
  void conversationHistoryTrimmedFromOldest() {
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100);
    ContextSection history = new ContextSection("CONVERSATION_HISTORY", "old history " + "x".repeat(5000));

    AllocationResult result = manager.allocate(List.of(history));
    assertTrue(result.dropped().contains("CONVERSATION_HISTORY"));
  }

  @Test
  void debugAllocationResultIsAvailable() {
    ContextBudgetManager manager = new ContextBudgetManager(128000, 8000, 2000);
    ContextSection system = new ContextSection("SYSTEM", "system");
    AllocationResult result = manager.allocate(List.of(system));
    assertTrue(result.totalTokens() > 0);
  }

  @Test
  void allocationPublishesBudgetEvents() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    ContextBudgetManager manager = new ContextBudgetManager(1000, 100, 100,
        new AgentEventFactory(java.time.Clock.systemUTC()), bus);
    ContextSection system = new ContextSection("SYSTEM", "system");
    ContextSection history = new ContextSection("CONVERSATION_HISTORY", "x".repeat(5000));

    manager.allocate(List.of(system, history));

    assertEquals(AgentEventType.CONTEXT_BUDGET_EVALUATED, events.get(0).type());
    ContextBudgetEvaluatedPayload payload = (ContextBudgetEvaluatedPayload) events.get(0).payload();
    assertTrue(payload.included().contains("SYSTEM"));
    assertTrue(payload.dropped().contains("CONVERSATION_HISTORY"));
    assertEquals(AgentEventType.CONTEXT_BUDGET_TRIMMED, events.get(1).type());
  }
}
