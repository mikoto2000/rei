package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** 計画アプリケーションサーバーRpcの結果と状態の契約を検証するテスト。 */
class PlanAppServerRpcTest {
  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  static final ObjectMapper JSON = new ObjectMapper();

  /** 検証に使う初期化。 */
  static final String INIT =
      "{\"id\":1,\"result\":{\"userAgent\":\"rei/0.154.0 (Linux)\",\"platformOs\":\"linux\"}}";

  /** 検証に使う利用者情報。 */
  static final String PROFILE =
      "{\"id\":2,\"result\":{\"data\":[{\"id\":\"rei-read\",\"allowed\":true}],\"nextCursor\":null}}";

  /** 検証に使うスレッド。 */
  static final String THREAD =
      "{\"id\":3,\"result\":{\"thread\":{\"id\":\"thread-1\"},\"cwd\":\"/isolated/workspace\",\"modelProvider\":\"rei_plan\",\"model\":\"model\",\"approvalPolicy\":\"untrusted\",\"approvalsReviewer\":\"user\",\"sandbox\":{\"type\":\"readOnly\",\"networkAccess\":false},\"activePermissionProfile\":{\"id\":\"rei-read\"},\"instructionSources\":[]}}";

  /** 検証に使う一回のやり取り。 */
  static final String TURN =
      "{\"id\":4,\"result\":{\"turn\":{\"id\":\"turn-1\",\"status\":\"inProgress\"}}}";

  /** 検証に使う使用状況。 */
  static final String USAGE =
      "{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tokenUsage\":{\"total\":{\"totalTokens\":17},\"last\":{\"totalTokens\":17}}}}";

  /** {@code done} の条件を再現するための補助情報を返す。 */
  static String done(String status) {
    return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\""
        + status
        + "\",\"items\":[{\"type\":\"agentMessage\",\"id\":\"message-1\",\"text\":\"answer\",\"phase\":\"final_answer\"}]}}}";
  }

  /** 検証条件を再現するための検証用に置き換えた。 */
  static final class Fake implements PlanAppServerRpc.Session {
    /** 検証に使うincoming。 */
    final Queue<String> incoming = new LinkedList<>();

    /** 検証に使う送信。 */
    final List<JsonNode> sent = new ArrayList<>();

    /** 検証に使う終了済み。 */
    boolean closed;

    /** 検証条件に必要な値を受け取り、Fakeを用意する。 */
    Fake(String... tail) {
      incoming.addAll(List.of(INIT, PROFILE, THREAD, TURN));
      incoming.addAll(List.of(tail));
    }

    /** {@code send} の条件を再現するため、検証用の操作や状態を扱う。 */
    public void send(String line) {
      try {
        sent.add(JSON.readTree(line));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    /** {@code poll} の条件を再現するための補助情報を返す。 */
    public String poll(Duration timeout) {
      return incoming.poll();
    }

    /** 検証で利用した対象の終了を扱う。 */
    public void close() {
      closed = true;
    }
  }

  /** 同一終端を利用することを確認する。 */
  @Test
  void pinnedWindowsHandshakeUsesSameTerminalAndBudgetProtocol() {
    var s = new Fake(USAGE, done("completed"));
    var frames = new ArrayList<>(s.incoming);
    frames.set(0, INIT.replace("0.154.0", "0.162.1").replace("linux", "windows"));
    s.incoming.clear();
    s.incoming.addAll(frames);
    var result =
        new PlanAppServerRpc("0.162.1", "windows")
            .execute(
                s,
                "/isolated/workspace",
                "model",
                "instructions",
                List.of(new PlanClient.Input("user", "task")),
                Duration.ofSeconds(1),
                4096,
                () -> false,
                () -> {},
                path -> "snapshot");
    assertEquals(PlanSseAssembler.State.COMPLETED, result.result().state());
    assertEquals(17, result.result().totalTokens());
    assertTrue(s.closed);
  }

  /** 判定期間連携の窓口について、Linuxを受け付けないことを確認する。 */
  @Test
  void windowsAdapterRejectsLinuxOrWrongVersionBeforeTurn() {
    var s = new Fake();
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(
                ChatGptException.class,
                () ->
                    new PlanAppServerRpc("0.162.1", "windows")
                        .execute(
                            s,
                            "cwd",
                            "model",
                            "instructions",
                            List.of(),
                            Duration.ofSeconds(1),
                            4096,
                            () -> false,
                            () -> {},
                            path -> ""))
            .code());
    assertTrue(s.closed);
    assertEquals(1, s.sent.size());
  }

  /** 検証用に、chatGPTとの連携の操作を実行する。 */
  PlanAppServerRpc.Outcome run(
      Fake s, BooleanSupplier stop, java.util.function.Function<String, String> read) {
    return new PlanAppServerRpc()
        .execute(
            s,
            "/isolated/workspace",
            "model",
            "instructions",
            List.of(new PlanClient.Input("user", "task")),
            Duration.ofSeconds(1),
            4096,
            stop,
            () -> {},
            read);
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void terminalCompletionAndUsageAreRequiredAndProcessCloses() {
    var s = new Fake(USAGE, done("completed"));
    var o = run(s, () -> false, p -> "source");
    assertEquals(PlanSseAssembler.State.COMPLETED, o.result().state());
    assertEquals("answer", o.result().text());
    assertEquals(17, o.result().totalTokens());
    assertTrue(s.closed);
    assertEquals("thread-1", o.trace().threadId());
    assertEquals("turn-1", o.trace().turnId());
    assertTrue(
        s.sent.get(0).path("params").path("capabilities").path("experimentalApi").asBoolean());
    assertEquals("rei-read", s.sent.get(3).path("params").path("permissions").asText());
  }

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void eofAfterDispatchNeverBecomesSuccess() {
    var o = run(new Fake(), () -> false, p -> "");
    assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN, o.result().state());
    assertEquals("", o.result().text());
  }

  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unsupportedVersionFailsBeforeThreadOrDispatch() {
    var s = new Fake();
    s.incoming.clear();
    s.incoming.add(INIT.replace("0.154.0", "0.155.0"));
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(ChatGptException.class, () -> run(s, () -> false, p -> "")).code());
    assertEquals(1, s.sent.size());
    assertTrue(s.closed);
  }

  /** ChatGPTとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void fileChangeApprovalIsDeniedAfterConfirmingStop() {
    var s =
        new Fake(
            "{\"id\":90,\"method\":\"item/fileChange/requestApproval\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}",
            USAGE,
            done("interrupted"));
    var o = run(s, () -> false, p -> "");
    assertEquals(1, o.trace().deniedRequests());
    assertEquals(
        "decline",
        s.sent.stream()
            .filter(n -> n.path("id").asInt() == 90)
            .findFirst()
            .orElseThrow()
            .path("result")
            .path("decision")
            .asText());
    assertEquals(PlanSseAssembler.State.FAILED, o.result().state());
  }

  /** ChatGPTとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void onlyBoundedGuardedSnapshotToolIsServed() {
    var s =
        new Fake(
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"callId\":\"call-1\",\"arguments\":{\"path\":\"docs/source.md\"}}}",
            USAGE,
            done("completed"));
    var paths = new ArrayList<String>();
    var o =
        run(
            s,
            () -> false,
            p -> {
              paths.add(p);
              return "approved snapshot";
            });
    assertEquals(List.of("docs/source.md"), paths);
    assertEquals(1, o.trace().sourceReads());
    assertTrue(
        s.sent.stream()
            .filter(n -> n.path("id").asInt() == 91)
            .findFirst()
            .orElseThrow()
            .path("result")
            .path("success")
            .asBoolean());
  }

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mismatchedProjectThreadNeverReachesReader() {
    var s =
        new Fake(
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"other\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"arguments\":{\"path\":\"docs/source.md\"}}}");
    var o =
        run(
            s,
            () -> false,
            p -> {
              fail("Cross-thread read");
              return "";
            });
    assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN, o.result().state());
  }

  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedRpcAndErrorsDoNotExposeRawValues() {
    var s = new Fake("not-json secret-value");
    var o = run(s, () -> false, p -> "");
    assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN, o.result().state());
    assertFalse(o.result().toString().contains("secret-value"));
  }

  /** 失敗一回のやり取りについて、使用状況を保持することを確認する。 */
  @Test
  void failedTurnRetainsUsageButNotUnvalidatedText() {
    var o = run(new Fake(USAGE, done("failed")), () -> false, p -> "");
    assertEquals(PlanSseAssembler.State.FAILED, o.result().state());
    assertEquals(17, o.result().totalTokens());
    assertEquals("", o.result().text());
  }

  /** ChatGPTとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBeforeDispatchDoesNotStartThread() {
    var s = new Fake();
    assertEquals(
        ChatGptException.Code.CANCELLED,
        assertThrows(ChatGptException.class, () -> run(s, () -> true, p -> "")).code());
    assertTrue(s.closed);
    assertTrue(s.sent.isEmpty());
  }

  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rpcErrorBeforeTurnDoesNotDispatchOrLeakRemoteMessage() {
    var s = new Fake();
    s.incoming.clear();
    s.incoming.add(INIT);
    s.incoming.add("{\"id\":2,\"error\":{\"code\":-32603,\"message\":\"secret-value\"}}");
    var error = assertThrows(ChatGptException.class, () -> run(s, () -> false, p -> ""));
    assertEquals(ChatGptException.Code.PROTOCOL, error.code());
    assertFalse(error.getMessage().contains("secret-value"));
    assertTrue(s.closed);
  }

  /** 失敗を伝えることを確認する。 */
  @Test
  void streamDisconnectIsUnknownEvenWhenNativeTurnReportsFailed() {
    var terminal =
        done("failed")
            .replace(
                "\"items\":",
                "\"error\":{\"codexErrorInfo\":{\"responseStreamDisconnected\":{\"httpStatusCode\":null}}},\"items\":");
    var result = run(new Fake(USAGE, terminal), () -> false, p -> "").result();
    assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN, result.state());
    assertNull(result.totalTokens());
  }

  /** ChatGPTとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void quotaAndUnauthorizedAreClassifiedWithoutRetry() {
    for (var code : List.of("usageLimitExceeded", "unauthorized")) {
      var terminal =
          done("failed")
              .replace("\"items\":", "\"error\":{\"codexErrorInfo\":\"" + code + "\"},\"items\":");
      var s = new Fake(terminal);
      var result = run(s, () -> false, p -> "").result();
      assertEquals(
          code.equals("unauthorized")
              ? ChatGptException.Code.AUTH_REQUIRED
              : ChatGptException.Code.USAGE_LIMIT,
          result.error());
      assertEquals(
          1, s.sent.stream().filter(n -> n.path("method").asText().equals("turn/start")).count());
    }
  }

  /** ChatGPTとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAfterTurnRequestsInterruptAndRequiresConfirmation() {
    var s = new Fake(done("interrupted"));
    var o = run(s, () -> s.incoming.size() == 1, p -> "");
    assertEquals(PlanSseAssembler.State.FAILED, o.result().state());
    assertEquals(ChatGptException.Code.CANCELLED, o.result().error());
    assertTrue(o.trace().interruptRequested());
    assertTrue(s.sent.stream().anyMatch(n -> n.path("method").asText().equals("turn/interrupt")));
    assertTrue(s.closed);
  }

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void refusedSnapshotCannotBecomeSuccessfulReview() {
    var s =
        new Fake(
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"callId\":\"call-1\",\"arguments\":{\"path\":\"outside\"}}}",
            done("interrupted"));
    var o =
        run(
            s,
            () -> false,
            p -> {
              throw ChatGptException.of(ChatGptException.Code.PERMISSION_DENIED);
            });
    assertEquals(PlanSseAssembler.State.FAILED, o.result().state());
    assertEquals(ChatGptException.Code.PERMISSION_DENIED, o.result().error());
    assertFalse(
        s.sent.stream()
            .filter(n -> n.path("id").asInt() == 91)
            .findFirst()
            .orElseThrow()
            .path("result")
            .path("success")
            .asBoolean());
  }
}
