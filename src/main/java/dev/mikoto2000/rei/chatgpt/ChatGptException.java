package dev.mikoto2000.rei.chatgpt;

/**
 * ChatGPTとの連携を継続できないチャットGPTの状況を伝える例外。
 *
 * <p>Diagnostics carry only app-defined codes, never remote bodies, URLs or exception causes.
 */
public final class ChatGptException extends RuntimeException {
  /** ChatGPTとの連携におけるコードの選択肢。 */
  public enum Code {
    /** 認証必須。 */
    AUTH_REQUIRED,
    /** 認証不正。 */
    AUTH_INVALID,
    /** CONSENT必須。 */
    CONSENT_REQUIRED,
    /** モデル利用不可。 */
    MODEL_UNAVAILABLE,
    /** 使用状況上限。 */
    USAGE_LIMIT,
    /** 使用状況利用不可。 */
    USAGE_UNAVAILABLE,
    /** 権限拒否。 */
    PERMISSION_DENIED,
    /** 受付経路利用不可。 */
    ROUTE_UNAVAILABLE,
    /** 通信規約。 */
    PROTOCOL,
    /** 通信経路。 */
    TRANSPORT,
    /** タイムアウト。 */
    TIMEOUT,
    /** キャンセル済み。 */
    CANCELLED,
    /** 受信上限。 */
    RECEIVE_LIMIT,
    /** 送信上限。 */
    SEND_LIMIT,
    /** 保存先利用不可。 */
    STORE_UNAVAILABLE,
    /** 取得元利用不可。 */
    SOURCE_UNAVAILABLE,
    /** PREPARATION失敗。 */
    PREPARATION_FAILED,
    /** 使用中。 */
    BUSY,
    /** 検証失敗。 */
    VALIDATION_FAILED
  }

  /** コード。 */
  private final Code code;

  /** 権限理由。 */
  private PermissionReason permissionReason;

  /** ChatGPTとの連携で用いる権限理由を返す。 */
  public PermissionReason permissionReason() {
    return permissionReason;
  }

  /** ChatGPTとの連携における権限理由の選択肢。 */
  public enum PermissionReason {
    /** 自動委譲無効状態。 */
    AUTO_DELEGATION_DISABLED("auto-delegation.enabled が無効です。管理者設定を確認してください。"),
    /** アカウント必須。 */
    ACCOUNT_REQUIRED("accountRef が未指定です。/chatgpt status のアカウントラベルを明示してください。"),
    /** アカウント否定許可。 */
    ACCOUNT_NOT_ALLOWED("accountRef が auto-delegation.allowed-accounts に含まれていません。"),
    /** モデル必須。 */
    MODEL_REQUIRED("modelId が未指定です。/chatgpt models --account <ラベル> で確認して指定してください。"),
    /** モデル否定許可。 */
    MODEL_NOT_ALLOWED("modelId が auto-delegation.allowed-models に含まれていません。"),
    /** 役割否定許可。 */
    ROLE_NOT_ALLOWED("agent または backendId が許可されていません。allowed-roles と委譲先を確認してください。"),
    /** 所有者否定排他。 */
    OWNER_NOT_EXCLUSIVE("Project を選択した親の EXCLUSIVE Run が必要です。子からの再委譲はできません。");

    /** 案内。 */
    final String guidance;

    /** 指定された値や利用先を使って、PermissionReasonを初期化する。 */
    PermissionReason(String guidance) {
      this.guidance = guidance;
    }
  }

  /** ChatGPTとの連携で用いる権限を返す。 */
  public static ChatGptException permission(PermissionReason reason) {
    var error =
        new ChatGptException(Code.PERMISSION_DENIED, reason.name() + ": " + reason.guidance);
    error.permissionReason = reason;
    return error;
  }

  /** ChatGPTとの連携における取得元失敗情報の選択肢。 */
  public enum SourceFailure {
    /** パス否定許可。 */
    PATH_NOT_ALLOWED(
        Code.PERMISSION_DENIED,
        "Project 内の通常ファイルを相対パスで指定してください。絶対パス、隠しパス、親ディレクトリ、Project 外の参照は送信できません。"),
    /** 否定見つかった。 */
    NOT_FOUND(Code.SOURCE_UNAVAILABLE, "対象ファイルが存在しません。sourceRefs のパスと現在の Project を確認してください。"),
    /** 読み取り失敗。 */
    READ_FAILED(Code.SOURCE_UNAVAILABLE, "対象資料を読み取れません。ファイルの状態と OS の読取権限を確認してください。"),
    /** 不正UTF8。 */
    INVALID_UTF8(Code.SOURCE_UNAVAILABLE, "対象資料を UTF-8 として読めません。UTF-8 のテキスト資料を指定してください。"),
    /** 内容RESTRICTED。 */
    CONTENT_RESTRICTED(
        Code.PERMISSION_DENIED,
        "資料の保護対象を安全に自動マスクできないため送信しません。指定位置の値の構造を確認してください。通常のパスワード、トークン、秘密鍵は送信用スナップショットで自動マスクします。"),
    /** 許容範囲を超える容量。 */
    TOO_LARGE(
        Code.SEND_LIMIT,
        "単独ファイルが送信上限 262144 bytes を超えています。ファイルを分けて指定してください。複数ファイルの合計超過は自動で二分割します。");

    /** コード。 */
    final Code code;

    /** 案内。 */
    final String guidance;

    /** 指定された値や利用先を使って、SourceFailureを初期化する。 */
    SourceFailure(Code code, String guidance) {
      this.code = code;
      this.guidance = guidance;
    }
  }

  /**
   * ChatGPTとの連携で用いる取得元を返す。
   *
   * <p>Identify only a request position, never file contents, a supplied path or exception text.
   */
  public static ChatGptException source(SourceFailure reason, int index) {
    if (index < 0 || index >= 16) throw new IllegalArgumentException("Invalid source position");
    return new ChatGptException(
        reason.code, "sourceRefs[" + index + "]: " + reason.name() + ": " + reason.guidance);
  }

  /** ChatGPTとの連携における委譲失敗情報の選択肢。 */
  public enum DelegationFailure {
    /** UNSPLITTABLE入力。 */
    UNSPLITTABLE_INPUT(
        Code.SEND_LIMIT,
        "1ファイルまたは指示文だけでも入力上限に収まりません。ファイル・指示文を小さくするか、rei.chatgpt-plan.execution.max-input-bytes（最大262144）を確認してください。ファイル内の自動分割は行いません。"),
    /** グループ呼び出し予算上限超過。 */
    GROUP_CALL_BUDGET_EXCEEDED(
        Code.PERMISSION_DENIED,
        "分割グループ数に対して呼び出し上限が不足しています。ロールの maxSteps と rei.chatgpt-plan.execution.max-iterations を確認してください。分割で予算はリセットしません。"),
    /** 要求内容RESTRICTED。 */
    REQUEST_CONTENT_RESTRICTED(
        Code.PERMISSION_DENIED, "要求本文またはロールの指示に保護対象の文字列を検出したため送信しません。値を伏せて委譲してください。"),
    /** 結果内容RESTRICTED。 */
    RESULT_CONTENT_RESTRICTED(Code.PERMISSION_DENIED, "子の回答に保護対象の文字列を検出したため、修正・追加要求として再送しません。"),
    /** 共有言語モデル呼び出し予算残量なし。 */
    SHARED_LLM_CALL_BUDGET_EXHAUSTED(
        Code.PERMISSION_DENIED,
        "親 Run の LLM 呼び出し予算が残っていません。rei.llm.output-limit.max-llm-calls-per-run と親の残予算を確認してください。"),
    /** 共有トークン予算残量なし。 */
    SHARED_TOKEN_BUDGET_EXHAUSTED(
        Code.PERMISSION_DENIED,
        "親 Run のトークン予算が残っていません。rei.llm.output-limit.max-total-tokens-per-run と親の残予算を確認してください。"),
    /** 共有使用状況不明。 */
    SHARED_USAGE_UNKNOWN(Code.USAGE_UNAVAILABLE, "親 Run の使用量が不明です。使用量を確認できるまで追加の委譲は停止します。"),
    /** 取得元作業ルート利用不可。 */
    SOURCE_ROOT_UNAVAILABLE(
        Code.SOURCE_UNAVAILABLE, "Project のルートを読み取れません。選択した Project のフォルダと OS の読取権限を確認してください。");

    /** コード。 */
    final Code code;

    /** 案内。 */
    final String guidance;

    /** 指定された値や利用先を使って、DelegationFailureを初期化する。 */
    DelegationFailure(Code code, String guidance) {
      this.code = code;
      this.guidance = guidance;
    }
  }

  /** ChatGPTとの連携で用いる委譲を返す。 */
  public static ChatGptException delegation(DelegationFailure reason) {
    return new ChatGptException(reason.code, reason.name() + ": " + reason.guidance);
  }

  /** 指定された値や利用先を使って、ChatGptExceptionを初期化する。 */
  public ChatGptException(Code code) {
    super("ChatGPT plan: " + code.name(), null, false, false);
    this.code = code;
  }

  /** 指定された値や利用先を使って、ChatGptExceptionを初期化する。 */
  private ChatGptException(Code code, String diagnostic) {
    super("ChatGPT plan: " + code.name() + " (" + diagnostic + ")", null, false, false);
    this.code = code;
  }

  /** ChatGPTとの連携で用いる応答を返す。 */
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

  /** ChatGPTとの連携で用いるコードを返す。 */
  public Code code() {
    return code;
  }

  /** ChatGPTとの連携で受け渡す情報を、指定された内容から作成する。 */
  public static ChatGptException of(Code code) {
    return new ChatGptException(code);
  }
}
