package dev.mikoto2000.rei.chatgpt;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * ChatGPTとの連携に必要な計画推定を扱う。
 *
 * <p>Role validation and Run ownership remain outside each versioned transport adapter.
 */
public interface PlanInference {
  /** ChatGPTとの連携で用いる推定履歴を返す。 */
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

  /** ChatGPTとの連携で用いる推定を返す。 */
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

  /** ChatGPTとの連携で用いる取り出し追跡情報を返す。 */
  default PlanAppServerRpc.Trace takeTrace() {
    return null;
  }

  /** ChatGPTとの連携で用いる接続先を返す。 */
  default String authority() {
    return "responses-v1";
  }

  /** ChatGPTとの連携に関するOSの機能予算を処理する。 */
  default void nativeBudget(
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation budget, int maxCalls) {}

  /** ChatGPTとの連携で用いる取り出しOSの機能トークン数を返す。 */
  default int takeNativeTokens() {
    return 0;
  }
}
