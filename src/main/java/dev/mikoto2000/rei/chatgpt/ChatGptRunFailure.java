package dev.mikoto2000.rei.chatgpt;

import dev.mikoto2000.rei.event.ErrorInformation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;

/** Share the controlled ChatGPT diagnostic between tool preflight and terminal Run events. */
public final class ChatGptRunFailure {
  private ChatGptRunFailure() {}

  public static ErrorInformation diagnostic(ChatGptException failure) {
    String guidance =
        failure.permissionReason() == null
            ? ""
            : switch (failure.permissionReason()) {
              case AUTO_DELEGATION_DISABLED ->
                  "設定 rei.chatgpt-plan.auto-delegation.enabled を管理者に確認してください。";
              case ACCOUNT_REQUIRED -> "/chatgpt status で確認したアカウントラベルを accountRef に明示して呼び出してください。";
              case ACCOUNT_NOT_ALLOWED ->
                  "accountRef をホストが許可したアカウントラベルに修正してください。許可一覧の設定: rei.chatgpt-plan.auto-delegation.allowed-accounts。";
              case MODEL_REQUIRED ->
                  "/chatgpt models --account <ラベル> とホストの許可一覧を確認し、modelId を明示して呼び出してください。";
              case MODEL_NOT_ALLOWED ->
                  "modelId をホストが許可したモデルに修正してください。許可一覧の設定: rei.chatgpt-plan.auto-delegation.allowed-models。";
              case ROLE_NOT_ALLOWED ->
                  "agent と backendId をホストが許可した値に修正してください。許可ロールの設定: rei.chatgpt-plan.allowed-roles。";
              case OWNER_NOT_EXCLUSIVE -> "Project を選択した親の EXCLUSIVE Run から委譲してください。";
            };
    // ChatGptException is final and its messages contain only application-defined diagnostics.
    // Never include the wrapping throwable, tool arguments, account/model values or remote body.
    return new ErrorInformation(
        failure.code() == ChatGptException.Code.PERMISSION_DENIED
            ? "ChatGptPermissionDenied"
            : "ChatGptFailure",
        failure.getMessage() + (guidance.isEmpty() ? "" : " " + guidance),
        "chatgpt_" + failure.code().name().toLowerCase(Locale.ROOT));
  }

  public static ErrorInformation find(Throwable error) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = error;
        cause != null && seen.size() < 32 && seen.add(cause);
        cause = cause.getCause())
      if (cause instanceof ChatGptException failure) return diagnostic(failure);
    return null;
  }
}
