package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ContextBudgetSessionTest {

  @Test
  void contextBudgetManagerIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ContextBudgetConfiguration.class);
      context.refresh();
      ContextBudgetManager first = context.getBean(ContextBudgetManager.class);
      ContextBudgetManager second = context.getBean(ContextBudgetManager.class);
      assertSame(first, second);
    }
  }
}
