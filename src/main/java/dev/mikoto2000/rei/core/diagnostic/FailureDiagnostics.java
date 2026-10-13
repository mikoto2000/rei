package dev.mikoto2000.rei.core.diagnostic;

import dev.mikoto2000.rei.event.ErrorInformation;

/** Server-only typed failure classification; no provider body or arbitrary exception message is exposed. */
public final class FailureDiagnostics {
  private FailureDiagnostics() {}

  /** Safe diagnostics for automated events; provider messages and response bodies are never copied. */
  public record Diagnostic(String code, String message) {
    public String display() { return code + ": " + message; }
  }

  public static ErrorInformation safeError(Throwable error) {
    var diagnostic = classify(error);
    return new ErrorInformation(error == null ? "UnknownFailure" : error.getClass().getSimpleName(),
        diagnostic.message(), diagnostic.code());
  }

  public static Diagnostic classify(Throwable error) {
    var causes = new java.util.ArrayList<Throwable>();
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (var current = error; current != null && causes.size() < 16 && seen.add(current); current = current.getCause())
      causes.add(current);
    for (var cause : causes)
      if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException
          || reactor.core.Exceptions.isCancel(cause))
        return diagnostic("cancelled", "処理が中断されました。保存済みの状態を確認してください。");
    for (var cause : causes) {
      if (cause instanceof dev.mikoto2000.rei.core.contextbudget.RawResultPageException page)
        return page.diagnostic();
      if (cause instanceof dev.mikoto2000.rei.workcontext.WorkContextExtractionException extraction)
        return extraction.diagnostic();
      if (cause instanceof com.openai.errors.OpenAIServiceException response) {
        String code = "";
        try { code = response.code().orElse(""); }
        catch (RuntimeException unavailable) { /* A malformed error body must not break the diagnostic itself. */ }
        if ("context_length_exceeded".equals(code))
          return diagnostic("provider_context_limit", "LLM の入力コンテキスト上限を超えました。入力や送信する履歴を減らしてください。");
        if ("insufficient_quota".equals(code))
          return diagnostic("provider_quota_exhausted", "LLM の利用枠が不足しています。利用枠・契約・請求設定を確認してください。");
        if (cause instanceof com.openai.errors.SseException && response.statusCode() >= 200 && response.statusCode() < 300)
          return diagnostic("invalid_response", "LLM のストリームにエラーが含まれていました。モデルと API の互換性・稼働状況を確認してください。");
        return http(response.statusCode());
      }
      if (cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException response)
        return http(response.getStatusCode().value());
      if (cause instanceof org.springframework.web.client.RestClientResponseException response)
        return http(response.getStatusCode().value());
    }
    for (var cause : causes) {
      if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.sql.SQLTimeoutException)
        return diagnostic("timeout", "応答待ちがタイムアウトしました。接続先とタイムアウト設定を確認し、実行結果を確認してから再開してください。");
      if (cause instanceof java.net.UnknownHostException)
        return diagnostic("dns_failure", "接続先の名前を解決できませんでした。ホスト名・DNS・ネットワーク設定を確認してください。");
      if (cause instanceof java.net.ConnectException || cause instanceof java.net.NoRouteToHostException)
        return diagnostic("connection_failed", "接続先へ接続できませんでした。サービスの起動・URL・プロキシ・ネットワークを確認してください。");
      if (cause instanceof javax.net.ssl.SSLException)
        return diagnostic("tls_failure", "TLS 接続に失敗しました。証明書・接続先・プロキシ設定を確認してください。");
    }
    for (var cause : causes) {
      if (cause instanceof tools.jackson.core.JacksonException
          || cause instanceof com.fasterxml.jackson.core.JsonProcessingException
          || cause instanceof com.openai.errors.OpenAIInvalidDataException)
        return diagnostic("invalid_json", "応答または保存データの形式を読み取れませんでした。指定した出力形式と保存データを確認してください。");
      if (cause instanceof java.nio.file.AccessDeniedException)
        return diagnostic("storage_access_denied", "ファイルへのアクセスが拒否されました。対象ファイル・ディレクトリの権限を確認してください。");
      if (cause instanceof java.nio.file.NoSuchFileException)
        return diagnostic("source_missing", "必要なファイルが見つかりません。指定したパスとファイルの存在を確認してください。");
      if (cause instanceof java.io.FileNotFoundException)
        return diagnostic("file_unavailable", "必要なファイルを開けません。ファイルの存在・パス・アクセス権限を確認してください。");
      if (cause instanceof org.sqlite.SQLiteException database)
        return sqlite(database.getResultCode().code & 0xff);
    }
    for (var cause : causes) {
      if (cause instanceof java.util.ConcurrentModificationException)
        return diagnostic("concurrent_update", "保存する前に別の更新が行われました。最新の保存状態を確認してから更新してください。");
      if (cause instanceof java.sql.SQLException || cause instanceof org.springframework.dao.DataAccessException)
        return diagnostic("database_failure", "データベースの操作に失敗しました。保存先の状態・権限・空き容量と保存済みの結果を確認してください。");
    }
    for (var cause : causes)
      if (cause instanceof java.io.IOException || cause instanceof java.io.UncheckedIOException
          || cause instanceof com.openai.errors.OpenAIIoException)
        return diagnostic("io_failure", "入出力に失敗しました。保存先・空き容量・接続を確認し、実行結果を確認してから再開してください。");
    for (var cause : causes) {
      if (cause instanceof java.util.concurrent.RejectedExecutionException)
        return diagnostic("queue_unavailable", "処理を受け付けるキューが満杯、または停止中です。進行中の処理とアプリの状態を確認してください。");
      if (cause instanceof SecurityException)
        return diagnostic("permission_denied", "操作の権限が拒否されました。操作内容と権限設定を確認してください。");
      if (cause instanceof IllegalArgumentException)
        return diagnostic("invalid_arguments", "引数または設定が不正です。直前の操作の引数と設定を確認してください。");
    }
    if (causes.stream().anyMatch(cause -> cause instanceof IllegalStateException))
      return diagnostic("invalid_state", "処理に必要な状態が整っていません。保存済みの状態と接続先の状態を確認してください。");
    return diagnostic("unknown_failure", "分類できないエラーが発生しました。実行結果とアプリのログを確認してください。");
  }

  private static Diagnostic http(int status) {
    String code;
    String guidance;
    switch (status) {
      case 401 -> { code="authentication_failed"; guidance="認証に失敗しました。利用する接続先と認証設定を確認してください。"; }
      case 403 -> { code="permission_denied"; guidance="接続先に操作を拒否されました。アカウント・モデル・権限設定を確認してください。"; }
      case 404 -> { code="provider_not_found"; guidance="接続先が見つかりません。API の URL とモデル名を確認してください。"; }
      case 408, 504 -> { code="timeout"; guidance="接続先で応答がタイムアウトしました。実行結果を確認してから再開してください。"; }
      case 413 -> { code="provider_request_too_large"; guidance="接続先のリクエストサイズ上限を超えました。送信する入力や履歴を減らしてください。"; }
      case 429 -> { code="rate_limited"; guidance="接続先の利用制限に達しました。利用枠と制限解除時刻を確認してください。"; }
      default -> {
        if (status >= 500) { code="provider_unavailable"; guidance="接続先でエラーが発生しました。接続先の稼働状況と実行結果を確認してください。"; }
        else { code="invalid_request"; guidance="接続先がリクエストを受け付けませんでした。引数・モデル・API 設定を確認してください。"; }
      }
    }
    return diagnostic(code, "HTTP " + status + ": " + guidance);
  }

  private static Diagnostic diagnostic(String code, String message) { return new Diagnostic(code, message); }

  private static Diagnostic sqlite(int code) {
    return switch (code) {
      case 5, 6 -> diagnostic("database_busy", "データベースが別の処理で使用中です。進行中の処理と保存済みの結果を確認してください。");
      case 13 -> diagnostic("storage_full", "データベースの保存先の空き容量が不足しています。空き容量と保存済みの結果を確認してください。");
      case 3, 8, 23 -> diagnostic("storage_access_denied", "データベースの保存先を開く、または書き込む権限がありません。保存先・権限・アプリの状態を確認してください。");
      case 14 -> diagnostic("database_unavailable", "データベースの保存先を開けません。保存先の存在・権限・アプリの状態を確認してください。");
      case 11, 26 -> diagnostic("database_corrupt", "データベースが破損しているか、対応形式ではありません。書き込みを続ける前に保存先とバックアップを確認してください。");
      case 10 -> diagnostic("database_io_failure", "データベースの入出力に失敗しました。保存先・権限・空き容量と保存済みの結果を確認してください。");
      default -> diagnostic("database_failure", "データベースの操作に失敗しました。保存先の状態とアプリのログを確認してください。");
    };
  }

}
