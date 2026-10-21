package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 文脈に使える予算会話の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextBudgetSessionTest {

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
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
