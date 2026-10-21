package dev.mikoto2000.rei.core.contextbudget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * モデルへ渡す文脈の管理を継続できない文脈圧縮の状況を伝える例外。
 *
 * <p>Safe diagnostics for summary failures: never carries provider messages, bodies or request
 * text.
 */
public final class ContextCompressionException extends RuntimeException {
  /** モデルへ渡す文脈の管理における理由の選択肢。 */
  public enum Reason {
    /** 入力に使える予算。 */
    INPUT_BUDGET(
        "summary_input_budget",
        "要約対象が入力上限を超えています。rei.context-compression.model-context-limit とモデル別上限を確認してください。"),
    /** 空要約。 */
    EMPTY_SUMMARY("summary_empty", "モデルが空の要約を返しました。要約用モデルの応答と出力設定を確認してください。"),
    /** 要約TOO長い形式。 */
    SUMMARY_TOO_LONG(
        "summary_too_long", "要約が保存上限を超えました。rei.context-compression.summary-tokens を確認してください。"),
    /** INSUFFICIENT音量の補正。 */
    INSUFFICIENT_GAIN(
        "summary_insufficient_gain",
        "要約しても十分に短くなりませんでした。rei.context-compression.minimum-compression-gain と summary-tokens を確認してください。"),
    /** 出力上限。 */
    OUTPUT_LIMIT(
        "summary_output_limit",
        "要約モデルが出力上限に達しました。思考トークンも上限を消費する場合があります。rei.context-compression.summary-tokens とモデルの出力設定を確認してください。"),
    /** タイムアウト。 */
    TIMEOUT(
        "summary_timeout",
        "要約の制限時間を超えました。接続先の応答速度と rei.context-compression.summary-timeout-seconds を確認してください。"),
    /** 認証。 */
    AUTHENTICATION("summary_authentication", "要約モデルへの認証またはアクセスが拒否されました。モデル接続先の認証・権限を確認してください。"),
    /** 頻度上限。 */
    RATE_LIMIT("summary_rate_limit", "要約モデルの利用枠・呼び出し制限に達しました。利用枠と接続先の制限を確認してください。"),
    /** モデル利用不可。 */
    MODEL_UNAVAILABLE("summary_model_unavailable", "要約モデルまたは接続先を利用できません。モデル名・接続先・稼働状態を確認してください。"),
    /** 通信経路。 */
    TRANSPORT("summary_transport", "要約モデルとの通信に失敗しました。接続先とネットワークの状態を確認してください。"),
    /** 要約保存。 */
    SUMMARY_STORAGE("summary_storage", "要約を保存できませんでした。Rei のデータ保存先の容量・権限を確認してください。"),
    /** 不正応答。 */
    INVALID_RESPONSE("summary_invalid_response", "要約モデルの応答形式が不正です。要約にはツール呼び出しを含まないテキスト応答が必要です。"),
    /** 不明。 */
    UNKNOWN("summary_unknown", "要約処理で分類できないエラーが発生しました。要約モデルの設定と接続先のログを確認してください。");

    /** コード。 */
    private final String code;

    /** 案内。 */
    private final String guidance;

    /** 指定された値や利用先を使って、Reasonを初期化する。 */
    Reason(String code, String guidance) {
      this.code = code;
      this.guidance = guidance;
    }

    /** モデルへ渡す文脈の管理で用いるコードを返す。 */
    public String code() {
      return code;
    }

    /** モデルへ渡す文脈の管理で用いる案内を返す。 */
    public String guidance() {
      return guidance;
    }
  }

  /** 理由。 */
  private final Reason reason;

  /** 実際のトークン数。 */
  private final long actualTokens;

  /** 上限トークン数。 */
  private final long limitTokens;

  /** 元の診断情報。 */
  private final dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic underlying;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextCompressionException(Reason reason) {
    this(reason, -1, -1);
  }

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextCompressionException(Reason reason, long actualTokens, long limitTokens) {
    this(reason, actualTokens, limitTokens, null);
  }

  /** 指定された値や利用先を使って、ContextCompressionExceptionを初期化する。 */
  private ContextCompressionException(
      Reason reason,
      long actualTokens,
      long limitTokens,
      dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic underlying) {
    super(Objects.requireNonNull(reason).code() + ": " + reason.guidance());
    this.reason = reason;
    this.actualTokens = actualTokens;
    this.limitTokens = limitTokens;
    this.underlying = underlying;
  }

  /** モデルへ渡す文脈の管理で用いる理由を返す。 */
  public Reason reason() {
    return reason;
  }

  /** モデルへ渡す文脈の管理で用いる実際のトークン数を返す。 */
  public long actualTokens() {
    return actualTokens;
  }

  /** モデルへ渡す文脈の管理で用いる上限トークン数を返す。 */
  public long limitTokens() {
    return limitTokens;
  }

  /** モデルへ渡す文脈の管理で用いる詳細一覧を返す。 */
  public String details() {
    return reason.guidance() + (underlying == null ? "" : " 原因: " + underlying.display());
  }

  /** モデルへ渡す文脈の管理で用いる保存失敗情報を返す。 */
  public static ContextCompressionException storageFailure(Throwable error) {
    return new ContextCompressionException(
        Reason.SUMMARY_STORAGE,
        -1,
        -1,
        dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error));
  }

  /**
   * モデルへ渡す文脈の管理で利用する情報を、受け取った形式から変換する。
   *
   * <p>Inspects only exception types and HTTP status codes; raw provider errors are never retained.
   */
  public static ContextCompressionException from(Throwable error) {
    Throwable original = error;
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (int depth = 0;
        error != null && depth < 16 && seen.add(error);
        depth++, error = error.getCause()) {
      if (error instanceof ContextCompressionException classified) return classified;
    }
    return classified(original);
  }

  /** モデルへ渡す文脈の管理で用いる分類済みを返す。 */
  private static ContextCompressionException classified(Throwable error) {
    var diagnostic = dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error);
    Reason reason =
        switch (diagnostic.code()) {
          case "timeout" -> Reason.TIMEOUT;
          case "authentication_failed", "permission_denied" -> Reason.AUTHENTICATION;
          case "rate_limited", "provider_quota_exhausted" -> Reason.RATE_LIMIT;
          case "provider_context_limit", "provider_request_too_large" -> Reason.INPUT_BUDGET;
          case "provider_not_found", "provider_unavailable", "queue_unavailable" ->
              Reason.MODEL_UNAVAILABLE;
          case "dns_failure", "connection_failed", "tls_failure", "io_failure" -> Reason.TRANSPORT;
          case "invalid_json", "invalid_response", "invalid_request" -> Reason.INVALID_RESPONSE;
          default -> Reason.UNKNOWN;
        };
    return new ContextCompressionException(reason, -1, -1, diagnostic);
  }
}
