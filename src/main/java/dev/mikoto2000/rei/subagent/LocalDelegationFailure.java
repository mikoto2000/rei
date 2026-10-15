package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.chat.BoundedToolLoop;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Value-free failure categories; provider bodies, tool arguments and exception messages stay
 * private.
 */
public final class LocalDelegationFailure {
  private LocalDelegationFailure() {}

  public static String describe(Throwable failure, boolean setup) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable last = failure;
    for (int i = 0;
        failure != null && i < 16 && seen.add(failure);
        i++, failure = failure.getCause()) {
      last = failure;
      if (failure instanceof ToolPermissionException permission)
        return (permission.decision() == dev.mikoto2000.rei.core.policy.PermissionDecision.DENY
                ? "TOOL_PERMISSION_DENIED"
                : "TOOL_PERMISSION_APPROVAL_REQUIRED")
            + ": 子のツール権限または承認が不足しています。ロールの権限と承認設定を確認してください。";
      if (failure instanceof SubAgentValidationException invalid)
        return validation(invalid.errors());
      if (failure instanceof BoundedToolLoop.SharedBudgetExceeded)
        return "SHARED_LLM_BUDGET_EXHAUSTED: 親と子の共有モデル呼び出し予算を使い切りました。";
      if (failure instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped)
        return "RUN_BUDGET_STOPPED: " + stopped.reason() + "。Run の残予算と停止条件を確認してください。";
      if (failure instanceof BoundedToolLoop.MaxStepsExceeded)
        return "MAX_STEPS_EXCEEDED: 子の最大ステップ数を超えました。対象資料を分割してください。";
      if (failure instanceof TimeoutException || failure instanceof java.net.SocketTimeoutException)
        return "TIMEOUT: 子の実行または通信が期限を超えました。対象資料と通信状態を確認してください。";
      if (failure instanceof CancellationException || failure instanceof InterruptedException)
        return "CANCELLED: 子の処理がキャンセルされました。";
      if (failure instanceof org.springframework.web.client.HttpStatusCodeException http) {
        int status = http.getStatusCode().value();
        return (status == 401 || status == 403
                ? "MODEL_AUTHENTICATION"
                : status == 429 ? "MODEL_RATE_LIMIT" : "MODEL_HTTP_ERROR")
            + ": HTTP "
            + status
            + "。モデル接続先の認証・利用枠・応答状態を確認してください。";
      }
      if (failure instanceof java.io.IOException
          || failure instanceof org.springframework.web.client.ResourceAccessException)
        return "MODEL_TRANSPORT: モデルまたはツールの通信・入出力に失敗しました。接続先と実行環境を確認してください。";
    }
    return (setup ? "SETUP_FAILED" : "INTERNAL_ERROR")
        + ": "
        + (last == null ? "Unknown" : last.getClass().getSimpleName())
        + "。子の設定と Backend のログを確認してください。";
  }

  public static String validation(List<ValidationError> errors) {
    String paths =
        errors.stream()
            .limit(4)
            .map(
                e ->
                    dev.mikoto2000.rei.event.CredentialRedactor.redact(e.path())
                        .replaceAll("[\\p{Cntrl}]", " "))
            .map(p -> p.length() > 80 ? p.substring(0, 80) : p)
            .collect(java.util.stream.Collectors.joining(", "));
    return "RESULT_VALIDATION_FAILED: 子の出力が結果スキーマ・検証条件に適合しません。検証箇所: " + paths;
  }
}
