package dev.mikoto2000.rei.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 失敗情報診断情報の結果と状態の契約を検証するテスト。 */
class FailureDiagnosticsTest {
  /** 実行上の問題の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @CsvSource({
    "401,authentication_failed",
    "403,permission_denied",
    "404,provider_not_found",
    "408,timeout",
    "413,provider_request_too_large",
    "429,rate_limited",
    "500,provider_unavailable",
    "504,timeout",
    "400,invalid_request"
  })
  void httpStatusIsClassifiedWithoutResponseBodyHeadersOrOuterMessage(int status, String code) {
    var headers = new org.springframework.http.HttpHeaders();
    headers.set("Authorization", "Bearer SECRET_HEADER");
    var response =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            status,
            "SECRET_STATUS",
            headers,
            "unstructured private evidence SECRET_BODY"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);
    var diagnostic =
        FailureDiagnostics.classify(new IllegalStateException("SECRET_OUTER", response));
    assertEquals(code, diagnostic.code());
    assertTrue(diagnostic.message().contains("HTTP " + status));
    assertFalse(diagnostic.display().contains("SECRET"));
    var safe = FailureDiagnostics.safeError(response);
    assertEquals(code, safe.code());
    assertFalse(safe.message().contains("SECRET"));
  }

  /** 実行上の問題の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sdkStructuredCodesAreAllowlistedAndOtherProviderFieldsArePrivate() {
    var context =
        com.openai.errors.BadRequestException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("context_length_exceeded")
                    .message("PRIVATE_CONTEXT")
                    .param("PRIVATE_PARAM")
                    .type("PRIVATE_TYPE")
                    .build())
            .build();
    assertEquals("provider_context_limit", FailureDiagnostics.classify(context).code());
    var quota =
        com.openai.errors.RateLimitException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("insufficient_quota")
                    .message("PRIVATE_QUOTA")
                    .param("PRIVATE_PARAM")
                    .type("PRIVATE_TYPE")
                    .build())
            .build();
    assertEquals("provider_quota_exhausted", FailureDiagnostics.classify(quota).code());
    var unknown =
        com.openai.errors.BadRequestException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("PRIVATE_CODE")
                    .message("PRIVATE_BODY")
                    .param("PRIVATE_PARAM")
                    .type("PRIVATE_TYPE")
                    .build())
            .build();
    assertEquals("invalid_request", FailureDiagnostics.classify(unknown).code());
    for (var failure : java.util.List.of(context, quota, unknown))
      assertFalse(FailureDiagnostics.classify(failure).display().contains("PRIVATE"));
  }

  /** 実行上の問題の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void sdkMalformedAndStreamErrorResponsesHaveSeparateSafeCauses() {
    assertEquals(
        "invalid_json",
        FailureDiagnostics.classify(
                new com.openai.errors.OpenAIInvalidDataException("PRIVATE_JSON"))
            .code());
    var stream =
        com.openai.errors.SseException.builder()
            .statusCode(200)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("PRIVATE_CODE")
                    .message("PRIVATE_STREAM")
                    .param("PRIVATE_PARAM")
                    .type("PRIVATE_TYPE")
                    .build())
            .build();
    assertEquals("invalid_response", FailureDiagnostics.classify(stream).code());
    assertFalse(FailureDiagnostics.classify(stream).display().contains("PRIVATE"));
  }

  /** 実行上の問題の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void nestedTransportAndFileCausesOverrideGenericWrappers() {
    assertEquals(
        "timeout",
        FailureDiagnostics.classify(
                new IllegalStateException(
                    "PRIVATE", new java.util.concurrent.TimeoutException("PRIVATE_TIMEOUT")))
            .code());
    assertEquals(
        "dns_failure",
        FailureDiagnostics.classify(
                new java.io.UncheckedIOException(new java.net.UnknownHostException("PRIVATE_HOST")))
            .code());
    assertEquals(
        "connection_failed",
        FailureDiagnostics.classify(
                new RuntimeException(new java.net.ConnectException("PRIVATE_URL")))
            .code());
    assertEquals(
        "tls_failure",
        FailureDiagnostics.classify(
                new RuntimeException(new javax.net.ssl.SSLException("PRIVATE_CERT")))
            .code());
    assertEquals(
        "storage_access_denied",
        FailureDiagnostics.classify(
                new IllegalStateException(new java.nio.file.AccessDeniedException("PRIVATE_PATH")))
            .code());
    assertEquals(
        "source_missing",
        FailureDiagnostics.classify(
                new IllegalStateException(new java.nio.file.NoSuchFileException("PRIVATE_PATH")))
            .code());
    assertEquals(
        "io_failure",
        FailureDiagnostics.classify(
                new IllegalStateException(new java.io.IOException("PRIVATE_IO")))
            .code());
  }

  /** 実行上の問題の診断で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unknownCausesAreBoundedAndDoNotCopyArbitraryMessages() {
    var first = new IllegalStateException("PRIVATE_OUTER");
    var second = new RuntimeException("PRIVATE_INNER", first);
    first.initCause(second);
    assertEquals("invalid_state", FailureDiagnostics.classify(first).code());
    assertFalse(FailureDiagnostics.classify(first).display().contains("PRIVATE"));
    Throwable deep = new java.net.SocketTimeoutException("PRIVATE_TIMEOUT");
    for (int i = 0; i < 20; i++) deep = new RuntimeException("PRIVATE_WRAPPER", deep);
    assertEquals("unknown_failure", FailureDiagnostics.classify(deep).code());
    assertEquals("unknown_failure", FailureDiagnostics.classify(null).code());
    assertEquals(
        "cancelled",
        FailureDiagnostics.classify(
                new RuntimeException(new InterruptedException("PRIVATE_CANCEL")))
            .code());
  }

  /** 実行上の問題の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void localDatabaseAndRevisionFailuresExposeOnlyTypedCauses() {
    var types =
        java.util.Map.of(
            org.sqlite.SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT,
            "database_busy",
            org.sqlite.SQLiteErrorCode.SQLITE_FULL,
            "storage_full",
            org.sqlite.SQLiteErrorCode.SQLITE_READONLY_DIRECTORY,
            "storage_access_denied",
            org.sqlite.SQLiteErrorCode.SQLITE_CORRUPT_INDEX,
            "database_corrupt",
            org.sqlite.SQLiteErrorCode.SQLITE_IOERR_WRITE,
            "database_io_failure");
    for (var type : types.entrySet()) {
      var error =
          new IllegalStateException(
              "PRIVATE_SQL_WRAPPER",
              new org.sqlite.SQLiteException("PRIVATE_SQL_DATA", type.getKey()));
      assertEquals(type.getValue(), FailureDiagnostics.classify(error).code());
      assertFalse(FailureDiagnostics.classify(error).display().contains("PRIVATE"));
    }
    assertEquals(
        "concurrent_update",
        FailureDiagnostics.classify(
                new java.util.ConcurrentModificationException("PRIVATE_REVISION"))
            .code());
    assertEquals(
        "database_failure",
        FailureDiagnostics.classify(new java.sql.SQLException("PRIVATE_SQL")).code());
    assertEquals(
        "invalid_json",
        FailureDiagnostics.classify(
                new com.fasterxml.jackson.core.JsonParseException(null, "PRIVATE_SAVED_JSON"))
            .code());
  }
}
