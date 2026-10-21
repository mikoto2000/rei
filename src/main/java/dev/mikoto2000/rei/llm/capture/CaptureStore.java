package dev.mikoto2000.rei.llm.capture;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 取得記録の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Bounded volatile originals. No body-bearing object has a generated toString.
 */
@Component
public final class CaptureStore {
  /** 本文上限／実行上限／試行上限。 */
  public static final int BODY_LIMIT = 1_048_576, RUN_LIMIT = 16_777_216, ATTEMPT_LIMIT = 32;

  /** 複製利用枠。 */
  private final java.util.concurrent.Semaphore copyPermit = new java.util.concurrent.Semaphore(1);

  /** モデル応答の記録で判断に用いる試行複製の条件を確認する。 */
  boolean tryCopy() {
    return copyPermit.tryAcquire();
  }

  /** 後続の処理が利用できるよう、複製を解放する。 */
  void releaseCopy() {
    copyPermit.release();
  }

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 予約。 */
  private Reservation reservation;

  /** 会話一覧。 */
  private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();

  /**
   * モデル応答の記録で受け渡す予約を表す。
   *
   * @param captureId 取得記録識別子
   * @param client クライアント
   * @param conversation 会話
   */
  private record Reservation(String captureId, Object client, String conversation) {}

  /**
   * モデル応答の記録で受け渡す会話付随情報を表す。
   *
   * @param captureId 取得記録識別子
   * @param conversationId 会話の識別子
   * @param submissionId submission識別子
   * @param runId 実行の識別子
   * @param rootRunId 作業ルート実行の識別子
   * @param parentRunId 親実行の識別子
   * @param attempts 試行回数
   * @param bytes バイト数
   * @param incomplete 未完了
   * @param ended 終了
   * @param outcome 最終結果
   */
  public record SessionInfo(
      String captureId,
      String conversationId,
      String submissionId,
      String runId,
      String rootRunId,
      String parentRunId,
      int attempts,
      int bytes,
      boolean incomplete,
      Instant ended,
      String outcome) {}

  /**
   * モデル応答の記録で受け渡す試行付随情報を表す。
   *
   * @param runId 実行の識別子
   * @param logicalCallId logical呼び出し識別子
   * @param attemptId 試行識別子
   * @param attemptNumber 試行数値
   * @param sendState 送信状態
   * @param captureState 取得記録状態
   * @param size 大きさ
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param contentType 内容の種類
   * @param capturedAt 観測日時
   * @param httpStatus HTTP通信状態
   * @param reason 理由
   */
  public record AttemptInfo(
      String runId,
      String logicalCallId,
      String attemptId,
      int attemptNumber,
      String sendState,
      String captureState,
      int size,
      String sha256,
      String contentType,
      Instant capturedAt,
      Integer httpStatus,
      String reason) {}

  /** モデル応答の記録に必要なLogical呼び出しを扱う。 */
  public static final class LogicalCall {
    /** 実行の識別子／識別子。 */
    final String runId, id = UUID.randomUUID().toString();

    /** 試行回数。 */
    final AtomicInteger attempts = new AtomicInteger();

    /** 指定された値や利用先を使って、LogicalCallを初期化する。 */
    LogicalCall(String runId) {
      this.runId = runId;
    }
  }

  /** モデル応答の記録に必要な試行を扱う。 */
  private static final class Attempt {
    /** 付随情報。 */
    AttemptInfo info;

    /** 本文。 */
    byte[] body;

    /** 指定された値や利用先を使って、Attemptを初期化する。 */
    Attempt(AttemptInfo info, byte[] body) {
      this.info = info;
      this.body = body;
    }
  }

  /** モデル応答の記録に必要な会話を扱う。 */
  private static final class Session {
    /** 取得記録識別子／会話／submission／実行。 */
    final String captureId, conversation, submission, run;

    /** 試行回数。 */
    final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

    /** バイト数。 */
    int bytes;

    /** 未完了。 */
    boolean incomplete;

    /** 終了。 */
    Instant ended;

    /** 最終結果。 */
    String outcome = "ACTIVE";

    /** 指定された値や利用先を使って、Sessionを初期化する。 */
    Session(Reservation r, String submission, String run) {
      captureId = r.captureId;
      conversation = r.conversation;
      this.submission = submission;
      this.run = run;
    }

    /** モデル応答の記録で用いる付随情報を返す。 */
    SessionInfo info() {
      return new SessionInfo(
          captureId,
          conversation,
          submission,
          run,
          run,
          null,
          attempts.size(),
          bytes,
          incomplete,
          ended,
          outcome);
    }
  }

  /** モデル応答の記録を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CaptureStore() {
    this(Clock.systemUTC());
  }

  /** 指定された値や利用先を使って、CaptureStoreを初期化する。 */
  public CaptureStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  /** 競合する処理と利用範囲を調整するため、取得記録保存先を確保する。 */
  public synchronized void reserve(Object client, String conversation) {
    prune();
    Objects.requireNonNull(client);
    Objects.requireNonNull(conversation);
    if (reservation != null || sessions.values().stream().anyMatch(s -> s.ended == null))
      throw new IllegalStateException("Capture reservation or recording already active");
    reservation = new Reservation(UUID.randomUUID().toString(), client, conversation);
  }

  /** モデル応答の記録において、無効に該当するか判定する。 */
  public synchronized boolean off(Object client, String conversation) {
    if (matches(client, conversation)) {
      reservation = null;
      return true;
    }
    return false;
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public synchronized String status(Object client, String conversation) {
    prune();
    return (matches(client, conversation) ? "RESERVED" : "OFF")
        + "; sessions="
        + sessions.size()
        + "; stored bytes="
        + sessions.values().stream().mapToInt(s -> s.bytes).sum()
        + "; active="
        + sessions.values().stream()
            .filter(s -> s.ended == null)
            .map(s -> s.run)
            .findFirst()
            .orElse("none")
        + "; limits: body=1048576/run=16777216/attempts=32/sessions=3/TTL=30min";
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  private boolean matches(Object client, String conversation) {
    return reservation != null
        && Objects.equals(reservation.client, client)
        && Objects.equals(reservation.conversation, conversation);
  }

  /** 要求を後続の処理へ引き渡すため、取得記録保存先を受け付ける。 */
  public synchronized boolean accept(
      Object client, String conversation, String submission, String run) {
    prune();
    if (!matches(client, conversation)) return false;
    while (sessions.size() >= 3) {
      var key =
          sessions.entrySet().stream()
              .filter(e -> e.getValue().ended != null)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow();
      erase(sessions.remove(key));
    }
    sessions.put(run, new Session(reservation, submission, run));
    reservation = null;
    return true;
  }

  /**
   * モデル応答の記録で用いるlogical呼び出しを返す。
   *
   * <p>Only a submitted exact root is eligible; callers explicitly exclude children.
   */
  public synchronized LogicalCall logicalCall(String run, String parent) {
    prune();
    var s = sessions.get(run);
    return parent == null && s != null && s.ended == null ? new LogicalCall(run) : null;
  }

  /** モデル応答の記録で用いる準備を返す。 */
  public synchronized AttemptInfo prepare(
      LogicalCall call, byte[] bytes, String contentType, String skipped) {
    if (call == null) return null;
    prune();
    var s = sessions.get(call.runId);
    if (s == null || s.ended != null) return null;
    int number = call.attempts.incrementAndGet();
    if (s.attempts.size() >= ATTEMPT_LIMIT) {
      s.incomplete = true;
      return null;
    }
    String state = skipped == null ? "COMPLETE" : skipped, reason = null;
    byte[] body = null;
    String hash = null;
    int size = bytes == null ? -1 : bytes.length;
    if (skipped == null && (bytes == null || size > BODY_LIMIT || size > RUN_LIMIT - s.bytes)) {
      state = bytes == null ? "CAPTURE_FAILED" : "SKIPPED_TOO_LARGE";
      reason = bytes == null ? "Body unavailable" : "Body or Run capacity exceeded";
    } else if (skipped == null) {
      try {
        body = bytes.clone();
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        s.bytes += size;
      } catch (java.security.NoSuchAlgorithmException unavailable) {
        state = "CAPTURE_FAILED";
        body = null;
        reason = "Hash unavailable";
      }
    }
    if (!"COMPLETE".equals(state)) {
      s.incomplete = true;
      if (reason == null) reason = state;
    }
    var info =
        new AttemptInfo(
            s.run,
            call.id,
            UUID.randomUUID().toString(),
            number,
            "PREPARED",
            state,
            size,
            hash,
            contentType,
            clock.instant(),
            null,
            reason);
    s.attempts.put(info.attemptId(), new Attempt(info, body));
    return info;
  }

  /** モデル応答の記録に関する最終結果を処理する。 */
  public synchronized void outcome(String id, String state, Integer status) {
    if (id == null) return;
    var a = findExact(id);
    if (a == null) return;
    var i = a.info;
    a.info =
        new AttemptInfo(
            i.runId(),
            i.logicalCallId(),
            i.attemptId(),
            i.attemptNumber(),
            state,
            i.captureState(),
            i.size(),
            i.sha256(),
            i.contentType(),
            i.capturedAt(),
            status,
            i.reason());
  }

  /** モデル応答の記録において、複製できるか判断する。 */
  public synchronized boolean canCopy(LogicalCall call) {
    var s = sessions.get(call.runId);
    return s != null && s.ended == null && s.attempts.size() < ATTEMPT_LIMIT && s.bytes < RUN_LIMIT;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public synchronized boolean enabled(LogicalCall call) {
    prune();
    var s = sessions.get(call.runId);
    return s != null && s.ended == null;
  }

  /** モデル応答の記録に関するmissedを処理する。 */
  public synchronized void missed(LogicalCall call) {
    var s = sessions.get(call.runId);
    if (s != null) s.incomplete = true;
  }

  /** 対象の実行をキャンセルする。 */
  public synchronized void cancel(LogicalCall call) {
    var s = sessions.get(call.runId);
    if (s != null)
      s.attempts.values().stream()
          .filter(
              a ->
                  a.info.logicalCallId().equals(call.id)
                      && List.of("PREPARED", "SEND_STARTED").contains(a.info.sendState()))
          .forEach(a -> outcome(a.info.attemptId(), "CANCELLED", null));
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public synchronized void finish(String run) {
    finish(run, "ENDED");
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public synchronized void finish(String run, String outcome) {
    var s = sessions.get(run);
    if (s != null && s.ended != null) {
      if (s.outcome.equals("ENDED") && !outcome.equals("ENDED")) s.outcome = outcome;
      return;
    }
    if (s != null) {
      s.ended = clock.instant();
      s.outcome = outcome;
      s.attempts.values().stream()
          .filter(a -> List.of("PREPARED", "SEND_STARTED").contains(a.info.sendState()))
          .forEach(a -> outcome(a.info.attemptId(), "OUTCOME_UNKNOWN", null));
    }
  }

  /** 要求された処理を利用できるよう、失敗を開始する。 */
  public synchronized void startFailed(String run) {
    finish(run, "START_FAILED");
  }

  /** モデル応答の記録で用いる会話一覧を返す。 */
  public synchronized List<SessionInfo> sessions() {
    prune();
    return sessions.values().stream().map(Session::info).toList();
  }

  /** モデル応答の記録で用いる試行回数を返す。 */
  public synchronized List<AttemptInfo> attempts(String run) {
    prune();
    var s = resolveSession(run);
    return s.attempts.values().stream().map(a -> a.info).toList();
  }

  /** モデル応答の記録で用いる試行を返す。 */
  public synchronized AttemptInfo attempt(String id) {
    prune();
    return resolveAttempt(id).info;
  }

  /** モデル応答の記録で用いる本文を返す。 */
  public synchronized byte[] body(String id) {
    prune();
    var a = resolveAttempt(id);
    if (a.body == null || !a.info.captureState().equals("COMPLETE"))
      throw new IllegalArgumentException("Complete original unavailable");
    return a.body.clone();
  }

  /** 指定や設定に応じた対象を利用するため、会話を確定する。 */
  private Session resolveSession(String id) {
    var found =
        sessions.values().stream()
            .filter(s -> s.run.equals(id) || id.length() >= 8 && s.run.startsWith(id))
            .toList();
    if (found.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous Run ID");
    return found.getFirst();
  }

  /** 指定や設定に応じた対象を利用するため、試行を確定する。 */
  private Attempt resolveAttempt(String id) {
    var found =
        sessions.values().stream()
            .flatMap(s -> s.attempts.values().stream())
            .filter(
                a ->
                    a.info.attemptId().equals(id)
                        || id.length() >= 8 && a.info.attemptId().startsWith(id))
            .toList();
    if (found.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous Attempt ID");
    return found.getFirst();
  }

  /** 対象を特定できるよう、完全一致を探す。 */
  private Attempt findExact(String id) {
    return sessions.values().stream()
        .map(s -> s.attempts.get(id))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** 指定された対象を削除する。 */
  public synchronized boolean delete(String run) {
    prune();
    Session s;
    try {
      s = resolveSession(run);
    } catch (IllegalArgumentException unknown) {
      return false;
    }
    sessions.remove(s.run);
    erase(s);
    return true;
  }

  /** 保持している情報を初期状態へ戻すため、取得記録保存先を消去する。 */
  public synchronized void clear() {
    reservation = null;
    sessions.values().forEach(CaptureStore::erase);
    sessions.clear();
  }

  /** モデル応答の記録に関する不要な情報の整理を処理する。 */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public synchronized void prune() {
    var iterator = sessions.values().iterator();
    while (iterator.hasNext()) {
      var s = iterator.next();
      if (s.ended != null && !s.ended.plus(Duration.ofMinutes(30)).isAfter(clock.instant())) {
        erase(s);
        iterator.remove();
      }
    }
  }

  /** モデル応答の記録に関するeraseを処理する。 */
  private static void erase(Session s) {
    s.attempts
        .values()
        .forEach(
            a -> {
              if (a.body != null) Arrays.fill(a.body, (byte) 0);
              a.body = null;
            });
    s.attempts.clear();
  }
}
