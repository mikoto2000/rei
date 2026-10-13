package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OutputLimitRunBudgetTest {

  @Test
  void consumesLlmCallsUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 2);

    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isFalse();
    assertThat(budget.remainingLlmCalls()).isZero();
    assertThat(budget.hasRemainingLlmCalls()).isFalse();
  }

  @Test
  void consumesReplansUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 10);

    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isFalse();
  }

  @Test
  void cannotReplanWhenLlmCallsAreExhausted() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 1);
    budget.tryConsumeLlmCall();

    assertThat(budget.tryConsumeReplan()).isFalse();
  }

  @Test void argumentCorrectionsUseTheirConfiguredAbsoluteLimit() {
    var budget=new OutputLimitRunBudget(2,20,null,0,3);
    assertThat(budget.maxArgumentCorrections()).isEqualTo(3);
    for(int used=0;used<3;used++) {
      assertThat(budget.remainingArgumentCorrections()).isEqualTo(3-used);
      assertThat(budget.tryCorrectArguments()).isTrue();
    }
    assertThat(budget.tryCorrectArguments()).isFalse();
    assertThat(budget.remainingArgumentCorrections()).isZero();
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(3);
  }

  @Test void legacyConstructorsUseFiveCorrectionsByDefault() {
    for(var budget:java.util.List.of(new OutputLimitRunBudget(2,20),
        new OutputLimitRunBudget(2,20,null),new OutputLimitRunBudget(2,20,null,0))) {
      assertThat(budget.maxArgumentCorrections()).isEqualTo(5);
      for(int used=0;used<5;used++)assertThat(budget.tryCorrectArguments()).isTrue();
      assertThat(budget.tryCorrectArguments()).isFalse();
    }
  }

  @Test void zeroDisablesCorrectionsAndNegativeLimitsAreRejected() {
    var budget=new OutputLimitRunBudget(2,20,null,0,0);
    assertThat(budget.tryCorrectArguments()).isFalse();
    assertThat(budget.snapshot().argumentCorrections()).isZero();
    assertThat(budget.remainingArgumentCorrections()).isZero();
    assertThatThrownBy(()->new OutputLimitRunBudget(2,20,null,0,-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void restoredUsageIsKeptWhenTheConfiguredLimitIsRaisedOrLowered() {
    var raised=new OutputLimitRunBudget(2,20,null,0,5);
    raised.restore(new OutputLimitRunBudget.Snapshot(0,2,0,false,0,2));
    assertThat(raised.remainingArgumentCorrections()).isEqualTo(3);
    for(int used=0;used<3;used++)assertThat(raised.tryCorrectArguments()).isTrue();
    assertThat(raised.tryCorrectArguments()).isFalse();

    var lowered=new OutputLimitRunBudget(2,20,null,0,3);
    lowered.restore(new OutputLimitRunBudget.Snapshot(0,2,0,false,0,5));
    assertThat(lowered.remainingArgumentCorrections()).isZero();
    assertThat(lowered.tryCorrectArguments()).isFalse();
    assertThat(lowered.snapshot().argumentCorrections()).isEqualTo(5);
  }

  @Test void restoringOlderProgressNeverReplenishesCorrectionsAndRejectsNegativeUsage() {
    var budget=new OutputLimitRunBudget(2,20,null,0,5);
    budget.restore(new OutputLimitRunBudget.Snapshot(0,2,0,false,0,4));
    budget.restore(new OutputLimitRunBudget.Snapshot(0,1,0,false,0,2));
    assertThat(budget.remainingArgumentCorrections()).isEqualTo(1);
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(4);
    assertThatThrownBy(()->budget.restore(new OutputLimitRunBudget.Snapshot(0,1,0,false,0,-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(4);
  }

  @Test void oldCheckpointJsonWithoutCorrectionCountRestoresWithNoUsage() throws Exception {
    // Match the Jackson 2 mapper used by PersistentCheckpointRepository for saved snapshots.
    var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        "{\"replans\":0,\"llmCalls\":1,\"totalTokens\":0,\"usageUnknown\":false}",OutputLimitRunBudget.Snapshot.class);
    var budget=new OutputLimitRunBudget(2,20,null,0,5);budget.restore(snapshot);
    assertThat(budget.remainingArgumentCorrections()).isEqualTo(5);
    assertThat(budget.snapshot().argumentCorrections()).isZero();
    assertThat(budget.remainingLlmCalls()).isEqualTo(19);
  }
}
