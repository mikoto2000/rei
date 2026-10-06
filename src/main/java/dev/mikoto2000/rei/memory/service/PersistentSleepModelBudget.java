package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.*;

/** Project lifetime accounting composed with the existing invocation budget. */
final class PersistentSleepModelBudget implements ModelCallBudget {
  private final SleepModelBudget invocation;
  private final MemoryRepository repository;
  private final MemoryProperties.Sleep properties;
  private final String project;
  PersistentSleepModelBudget(MemoryRepository repository,MemoryProperties.Sleep properties,String project,Runnable check) {
    this.repository=repository;this.properties=properties;this.project=project;
    invocation=new SleepModelBudget(properties,check);
  }
  public void run() {
    invocation.run();
    repository.reserveSleepModelCall(project,properties.maxLlmCallsPerProject(),properties.maxTotalTokensPerProject());
  }
  public boolean tokenLimitEnabled() {return invocation.tokenLimitEnabled()||properties.maxTotalTokensPerProject()>0;}
  public void recordTotalTokens(Integer tokens) {
    if(properties.maxTotalTokensPerProject()>0) {
      repository.recordSleepTokens(project,tokens);
      if(tokens==null||tokens<0)throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
      repository.checkSleepTokenBudget(project,properties.maxTotalTokensPerProject());
    }
    invocation.recordTotalTokens(tokens);
  }
}
