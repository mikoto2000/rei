package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API例外の要求や通知を受け付ける。 */
@RestControllerAdvice(basePackages = "dev.mikoto2000.rei.web")
public class ApiExceptionHandler {
  @ExceptionHandler(dev.mikoto2000.rei.secretary.SecretaryProvider.Failure.class)
  public ResponseEntity<Error> externalServiceFailure(
      dev.mikoto2000.rei.secretary.SecretaryProvider.Failure failure) {
    String code = failure.code().matches("[A-Z_]{1,64}") ? failure.code() : "UNAVAILABLE";
    return ResponseEntity.status(code.equals("RATE_LIMITED") ? 429 : 503)
        .header("Retry-After", Integer.toString(failure.retrySeconds()))
        .body(new Error("External service: " + code));
  }

  @ExceptionHandler(dev.mikoto2000.rei.secretary.SecretaryConflictException.class)
  public ResponseEntity<Error> secretaryConflict() {
    return ResponseEntity.status(409).body(new Error("Secretary revision changed"));
  }

  /**
   * Web APIによる操作で受け渡す使用中を表す。
   *
   * @param message メッセージ
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   */
  public record Busy(String message, String projectId, String sessionId, String runId) {}

  /** Web APIによる操作で用いる会話使用中を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.run.SessionBusyException.class)
  public ResponseEntity<Busy> sessionBusy(
      dev.mikoto2000.rei.application.run.SessionBusyException error) {
    return ResponseEntity.status(409)
        .body(
            new Busy(
                "Session already has an active Run",
                error.projectId(),
                error.sessionId(),
                error.runId()));
  }

  /** Web APIによる操作で用いる受付記録競合を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.run.IdempotencyConflictException.class)
  public ResponseEntity<Error> receiptConflict() {
    return ResponseEntity.status(409).body(new Error("Idempotency-Key belongs to another request"));
  }

  /** Web APIによる操作で用いる受付記録期限切れを返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.run.IdempotencyExpiredException.class)
  public ResponseEntity<Error> receiptExpired() {
    return ResponseEntity.status(410)
        .body(new Error("Receipt expired; automatic re-execution is forbidden"));
  }

  /** Web APIによる操作で用いる成果物を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.artifact.ArtifactException.class)
  public ResponseEntity<Error> artifact(dev.mikoto2000.rei.artifact.ArtifactException error) {
    int status =
        switch (error.code()) {
          case CAPACITY -> 507;
          case MISSING, EXPIRED, DELETED -> 410;
          case STALE, UNAVAILABLE -> 409;
          default -> 500;
        };
    return ResponseEntity.status(status).body(new Error("Artifact: " + error.code()));
  }

  /** 依頼された操作を進めるため、許容件数を実行する。 */
  @ExceptionHandler(dev.mikoto2000.rei.core.chat.ProjectRunQueue.CapacityExceededException.class)
  public ResponseEntity<Error> runCapacity() {
    return ResponseEntity.status(429).body(new Error("Run admission capacity reached"));
  }

  /** Web APIによる操作で用いる再開用の記録を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.checkpoint.CheckpointException.class)
  public ResponseEntity<?> checkpoint(dev.mikoto2000.rei.checkpoint.CheckpointException error) {
    int status =
        switch (error.code()) {
          case CAPACITY -> 507;
          case CORRUPT_CHECKPOINT, INCOMPATIBLE_SCHEMA -> 422;
          default -> 409;
        };
    if (error.code() == dev.mikoto2000.rei.checkpoint.CheckpointException.Code.CAPACITY
        && error.capacityDetail() != null)
      return ResponseEntity.status(status)
          .body(
              new CheckpointCapacityError(error.getMessage(), "CAPACITY", error.capacityDetail()));
    return ResponseEntity.status(status).body(new Error("Checkpoint: " + error.code()));
  }

  /**
   * Web APIによる操作で受け渡す再開用の記録許容件数エラーを表す。
   *
   * @param message メッセージ
   * @param code コード
   * @param usage 使用状況
   */
  public record CheckpointCapacityError(
      String message,
      String code,
      dev.mikoto2000.rei.checkpoint.CheckpointException.CapacityDetail usage) {}

  /** Web APIによる操作で用いる作業文脈競合を返す。 */
  @ExceptionHandler(java.util.ConcurrentModificationException.class)
  public ResponseEntity<Error> workContextConflict() {
    return ResponseEntity.status(409).body(new Error("Work Context revision changed"));
  }

  /** Web APIによる操作で用いる操作競合を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.state.OperationConflictException.class)
  public ResponseEntity<Error> operationConflict() {
    return ResponseEntity.status(409).body(new Error("Resource conflict"));
  }

  /** Web APIによる操作で用いる不正日付を返す。 */
  @ExceptionHandler(java.time.format.DateTimeParseException.class)
  public ResponseEntity<Error> invalidDate() {
    return invalidRequest();
  }

  /** Web APIによる操作で用いる形式不正要求を返す。 */
  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.beans.TypeMismatchException.class
  })
  public ResponseEntity<Error> malformedRequest() {
    return ResponseEntity.badRequest().body(new Error("Invalid request"));
  }

  /** Web APIによる操作で用いる想定外を返す。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Error> unexpected(Exception exception) {
    if (exception instanceof org.springframework.web.ErrorResponse response)
      return ResponseEntity.status(response.getStatusCode()).body(new Error("Request rejected"));
    return ResponseEntity.status(500).body(new Error("Internal server error"));
  }

  /** Web APIによる操作で用いる再送間隔を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.event.ReplayGapException.class)
  public ResponseEntity<Error> replayGap() {
    return ResponseEntity.status(409)
        .body(new Error("Required run event history is no longer retained"));
  }

  /** Web APIによる操作で用いる補助資料否定見つかったを返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.run.ResourceNotFoundException.class)
  public ResponseEntity<Error> resourceNotFound() {
    return ResponseEntity.status(404).body(new Error("Resource not found"));
  }

  /** Web APIによる操作で用いる会話競合を返す。 */
  @ExceptionHandler(dev.mikoto2000.rei.application.run.SessionConflictException.class)
  public ResponseEntity<Error> sessionConflict() {
    return ResponseEntity.status(409).body(new Error("Session belongs to another project"));
  }

  /** Web APIによる操作で用いる不正要求を返す。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Error> invalidRequest() {
    return ResponseEntity.badRequest().body(new Error("Invalid request"));
  }

  /**
   * Web APIによる操作で受け渡すエラーを表す。
   *
   * @param message メッセージ
   */
  public record Error(String message) {}

  /** Web APIによる操作で用いる否定見つかったを返す。 */
  @ExceptionHandler(RunNotFoundException.class)
  public ResponseEntity<Error> notFound() {
    return ResponseEntity.status(404).body(new Error("Run not found"));
  }
}
