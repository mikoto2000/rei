package dev.mikoto2000.rei.chatgpt;

/** Diagnostics carry only app-defined codes, never remote bodies, URLs or exception causes. */
public final class ChatGptException extends RuntimeException {
  public enum Code {
    AUTH_REQUIRED,
    AUTH_INVALID,
    CONSENT_REQUIRED,
    MODEL_UNAVAILABLE,
    USAGE_LIMIT,
    USAGE_UNAVAILABLE,
    PERMISSION_DENIED,
    ROUTE_UNAVAILABLE,
    PROTOCOL,
    TRANSPORT,
    TIMEOUT,
    CANCELLED,
    RECEIVE_LIMIT,
    SEND_LIMIT,
    STORE_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    PREPARATION_FAILED,
    BUSY,
    VALIDATION_FAILED
  }

  private final Code code;
  private PermissionReason permissionReason;

  public PermissionReason permissionReason() {
    return permissionReason;
  }

  public enum PermissionReason {
    AUTO_DELEGATION_DISABLED("auto-delegation.enabled が無効です。管理者設定を確認してください。"),
    ACCOUNT_REQUIRED("accountRef が未指定です。/chatgpt status のアカウントラベルを明示してください。"),
    ACCOUNT_NOT_ALLOWED("accountRef が auto-delegation.allowed-accounts に含まれていません。"),
    MODEL_REQUIRED("modelId が未指定です。/chatgpt models --account <ラベル> で確認して指定してください。"),
    MODEL_NOT_ALLOWED("modelId が auto-delegation.allowed-models に含まれていません。"),
    ROLE_NOT_ALLOWED("agent または backendId が許可されていません。allowed-roles と委譲先を確認してください。"),
    OWNER_NOT_EXCLUSIVE("Project を選択した親の EXCLUSIVE Run が必要です。子からの再委譲はできません。");
    final String guidance;

    PermissionReason(String guidance) {
      this.guidance = guidance;
    }
  }

  public static ChatGptException permission(PermissionReason reason) {
    var error =
        new ChatGptException(Code.PERMISSION_DENIED, reason.name() + ": " + reason.guidance);
    error.permissionReason = reason;
    return error;
  }

  public enum SourceFailure {
    PATH_NOT_ALLOWED(
        Code.PERMISSION_DENIED,
        "Project 内の通常ファイルを相対パスで指定してください。絶対パス、隠しパス、親ディレクトリ、Project 外の参照は送信できません。"),
    NOT_FOUND(Code.SOURCE_UNAVAILABLE, "対象ファイルが存在しません。sourceRefs のパスと現在の Project を確認してください。"),
    READ_FAILED(Code.SOURCE_UNAVAILABLE, "対象資料を読み取れません。ファイルの状態と OS の読取権限を確認してください。"),
    INVALID_UTF8(Code.SOURCE_UNAVAILABLE, "対象資料を UTF-8 として読めません。UTF-8 のテキスト資料を指定してください。"),
    CONTENT_RESTRICTED(
        Code.PERMISSION_DENIED,
        "資料の保護対象を安全に自動マスクできないため送信しません。指定位置の値の構造を確認してください。通常のパスワード、トークン、秘密鍵は送信用スナップショットで自動マスクします。"),
    TOO_LARGE(
        Code.SEND_LIMIT,
        "単独ファイルが送信上限 262144 bytes を超えています。ファイルを分けて指定してください。複数ファイルの合計超過は自動で二分割します。");
    final Code code;
    final String guidance;

    SourceFailure(Code code, String guidance) {
      this.code = code;
      this.guidance = guidance;
    }
  }

  /** Identify only a request position, never file contents, a supplied path or exception text. */
  public static ChatGptException source(SourceFailure reason, int index) {
    if (index < 0 || index >= 16) throw new IllegalArgumentException("Invalid source position");
    return new ChatGptException(
        reason.code, "sourceRefs[" + index + "]: " + reason.name() + ": " + reason.guidance);
  }

  public enum DelegationFailure {
    UNSPLITTABLE_INPUT(
        Code.SEND_LIMIT,
        "1ファイルまたは指示文だけでも入力上限に収まりません。ファイル・指示文を小さくするか、rei.chatgpt-plan.execution.max-input-bytes（最大262144）を確認してください。ファイル内の自動分割は行いません。"),
    GROUP_CALL_BUDGET_EXCEEDED(
        Code.PERMISSION_DENIED,
        "分割グループ数に対して呼び出し上限が不足しています。ロールの maxSteps と rei.chatgpt-plan.execution.max-iterations を確認してください。分割で予算はリセットしません。"),
    REQUEST_CONTENT_RESTRICTED(
        Code.PERMISSION_DENIED, "要求本文またはロールの指示に保護対象の文字列を検出したため送信しません。値を伏せて委譲してください。"),
    RESULT_CONTENT_RESTRICTED(Code.PERMISSION_DENIED, "子の回答に保護対象の文字列を検出したため、修正・追加要求として再送しません。"),
    SHARED_LLM_CALL_BUDGET_EXHAUSTED(
        Code.PERMISSION_DENIED,
        "親 Run の LLM 呼び出し予算が残っていません。rei.llm.output-limit.max-llm-calls-per-run と親の残予算を確認してください。"),
    SHARED_TOKEN_BUDGET_EXHAUSTED(
        Code.PERMISSION_DENIED,
        "親 Run のトークン予算が残っていません。rei.llm.output-limit.max-total-tokens-per-run と親の残予算を確認してください。"),
    SHARED_USAGE_UNKNOWN(Code.USAGE_UNAVAILABLE, "親 Run の使用量が不明です。使用量を確認できるまで追加の委譲は停止します。"),
    SOURCE_ROOT_UNAVAILABLE(
        Code.SOURCE_UNAVAILABLE, "Project のルートを読み取れません。選択した Project のフォルダと OS の読取権限を確認してください。");
    final Code code;
    final String guidance;

    DelegationFailure(Code code, String guidance) {
      this.code = code;
      this.guidance = guidance;
    }
  }

  public static ChatGptException delegation(DelegationFailure reason) {
    return new ChatGptException(reason.code, reason.name() + ": " + reason.guidance);
  }

  public ChatGptException(Code code) {
    super("ChatGPT plan: " + code.name(), null, false, false);
    this.code = code;
  }

  private ChatGptException(Code code, String diagnostic) {
    super("ChatGPT plan: " + code.name() + " (" + diagnostic + ")", null, false, false);
    this.code = code;
  }

  static ChatGptException response(
      Code code,
      String operation,
      int status,
      String mediaType,
      int received,
      int limit,
      String apiCode) {
    // All strings here are local enum-like values; never include remote header/body text.
    String safeType =
        switch (mediaType) {
          case "application/json", "text/event-stream" -> mediaType;
          case "" -> "missing";
          default -> "unsupported";
        };
    String safeApiCode =
        switch (apiCode) {
          case "subscription_sharing_usage_limit_exceeded",
              "subscription_sharing_usage_unavailable",
              "subscription_sharing_unsupported_capability",
              "subscription_sharing_route_not_supported",
              "subscription_sharing_user_unavailable",
              "model_not_found",
              "model_not_available",
              "subscription_sharing_invalid_user",
              "subscription_sharing_user_not_eligible",
              "chatpass_v2_scope_not_authorized",
              "chatpass_v2_invalid_authorization_context" ->
              apiCode;
          default -> "";
        };
    return new ChatGptException(
        code,
        "operation="
            + operation
            + ", httpStatus="
            + status
            + ", contentType="
            + safeType
            + (received < 0 ? "" : ", receivedBytes=" + received)
            + ", receiveLimitBytes="
            + limit
            + (received > limit ? ", bodyTruncated=true" : "")
            + (safeApiCode.isEmpty() ? "" : ", apiErrorCode=" + safeApiCode));
  }

  public Code code() {
    return code;
  }

  public static ChatGptException of(Code code) {
    return new ChatGptException(code);
  }
}
