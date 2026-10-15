package dev.mikoto2000.rei.chatgpt;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Role validation and Run ownership remain outside each versioned transport adapter. */
public interface PlanInference {
  PlanSseAssembler.Result inferHistory(
      String account,
      String model,
      String instructions,
      List<PlanClient.Input> input,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled,
      Runnable onDispatch);

  default PlanSseAssembler.Result infer(
      String account,
      String model,
      String instructions,
      String task,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled,
      Runnable onDispatch) {
    return inferHistory(
        account,
        model,
        instructions,
        List.of(new PlanClient.Input("user", task)),
        timeout,
        sendLimit,
        receiveLimit,
        cancelled,
        onDispatch);
  }

  default PlanAppServerRpc.Trace takeTrace() {
    return null;
  }

  default String authority() {
    return "responses-v1";
  }

  default void nativeBudget(
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation budget, int maxCalls) {}

  default int takeNativeTokens() {
    return 0;
  }
}
