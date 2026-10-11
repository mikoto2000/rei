package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PlanSseAssemblerTest {
  static String event(String type, String response) {
    return "event: "
        + type
        + "\ndata: {\"type\":\""
        + type
        + "\",\"response\":"
        + response
        + "}\n\n";
  }

  static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  static final String COMPLETED =
      "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"total_tokens\":17}}";

  @Test
  void completedUsesTerminalOutputAndUsage() {
    var result =
        new PlanSseAssembler().read(stream(event("response.completed", COMPLETED)), 1048576);
    assertEquals(PlanSseAssembler.State.COMPLETED, result.state());
    assertEquals("hello", result.text());
    assertEquals(17, result.totalTokens());
  }

  @Test
  void ambiguousJsonAndMalformedUtf8AreRejected() {
    String duplicate =
        "data: {\"type\":\"response.failed\",\"type\":\"response.completed\",\"response\":"
            + COMPLETED
            + "}\n\n";
    String trailing =
        "data: {\"type\":\"response.completed\",\"response\":" + COMPLETED + "} {}\n\n";
    for (String input : new String[] {duplicate, trailing})
      assertEquals(
          ChatGptException.Code.PROTOCOL,
          assertThrows(
                  ChatGptException.class, () -> new PlanSseAssembler().read(stream(input), 10000))
              .code());
    byte[] malformed = new byte[] {(byte) 0xC3, (byte) 0x28, 10, 10};
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(
                ChatGptException.class,
                () -> new PlanSseAssembler().read(new ByteArrayInputStream(malformed), 10000))
            .code());
  }

  @Test
  void fragmentedCrLfAndCommentsAreAssembled() {
    var data =
        (":heartbeat\r\n\r\n" + event("response.completed", COMPLETED).replace("\n", "\r\n"))
            .getBytes(StandardCharsets.UTF_8);
    var input =
        new ByteArrayInputStream(data) {
          public synchronized int read(byte[] b, int o, int n) {
            return super.read(b, o, Math.min(n, 1));
          }
        };
    assertEquals("hello", new PlanSseAssembler().read(input, 1048576).text());
  }

  @Test
  void partialOutputAndMissingTerminalAreUnknown() {
    var result =
        new PlanSseAssembler()
            .read(
                stream(
                    "data:"
                        + " {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\"}\n\n"),
                10000);
    assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN, result.state());
    assertEquals("", result.text());
  }

  @Test
  void unfinishedFrameIsNotSuccess() {
    assertEquals(
        PlanSseAssembler.State.OUTCOME_UNKNOWN,
        new PlanSseAssembler()
            .read(stream(event("response.completed", COMPLETED).strip()), 10000)
            .state());
  }

  @Test
  void failedUsageLimitAndUnavailableAreDifferent() {
    for (var code :
        new String[] {
          "subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable"
        }) {
      var result =
          new PlanSseAssembler()
              .read(
                  stream(event("response.failed", "{\"error\":{\"code\":\"" + code + "\"}}")),
                  10000);
      assertEquals(PlanSseAssembler.State.FAILED, result.state());
      assertEquals(
          code.equals("subscription_sharing_usage_limit_exceeded")
              ? ChatGptException.Code.USAGE_LIMIT
              : ChatGptException.Code.USAGE_UNAVAILABLE,
          result.error());
    }
  }

  @Test
  void incompleteIsDistinct() {
    assertEquals(
        PlanSseAssembler.State.INCOMPLETE,
        new PlanSseAssembler().read(stream(event("response.incomplete", "{}")), 10000).state());
  }

  @Test
  void receiveLimitAndMalformedJsonAreRejected() {
    assertThrows(
        ChatGptException.class,
        () -> new PlanSseAssembler().read(stream(event("response.completed", COMPLETED)), 10));
    assertThrows(
        ChatGptException.class,
        () -> new PlanSseAssembler().read(stream("data: garbage\n\n"), 10000));
  }

  @Test
  void toolOutputNeverRunsAndIsRejected() {
    assertThrows(
        ChatGptException.class,
        () ->
            new PlanSseAssembler()
                .read(
                    stream(
                        event(
                            "response.completed",
                            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"writeFile\"}]}")),
                    10000));
  }

  static String frame(String json) {
    return "data: " + json + "\n\n";
  }

  static final String EMPTY_COMPLETED =
      "{\"status\":\"completed\",\"output\":[],\"usage\":{\"total_tokens\":34}}";

  static String delta(String text) {
    return frame(
        "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\""
            + text
            + "\"}");
  }

  @Test
  void streamedTextIsReturnedOnlyAfterCompletedEvenWhenTerminalOutputIsEmpty() {
    var input = delta("Hello, ") + delta("world!") + event("response.completed", EMPTY_COMPLETED);
    var result = new PlanSseAssembler().read(stream(input), 1048576);
    assertEquals(PlanSseAssembler.State.COMPLETED, result.state());
    assertEquals("Hello, world!", result.text());
    assertEquals(34, result.totalTokens());
    for (String terminal :
        new String[] {"", event("response.failed", "{}"), event("response.incomplete", "{}")}) {
      var interrupted =
          new PlanSseAssembler().read(stream(delta("private-response") + terminal), 1048576);
      assertNotEquals(PlanSseAssembler.State.COMPLETED, interrupted.state());
      assertEquals("", interrupted.text());
      assertFalse(interrupted.toString().contains("private-response"));
    }
  }

  @Test
  void finalizedTextReplacesDeltasAndTerminalTextRemainsAuthoritative() {
    String done =
        frame(
            "{\"type\":\"response.output_text.done\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello,"
                + " world!\"}");
    assertEquals(
        "Hello, world!",
        new PlanSseAssembler()
            .read(stream(delta("old") + done + event("response.completed", EMPTY_COMPLETED)), 10000)
            .text());
    assertEquals(
        "hello",
        new PlanSseAssembler()
            .read(stream(delta("old") + done + event("response.completed", COMPLETED)), 10000)
            .text());
  }

  @Test
  void safeDiagnosticsDescribeEmptyTerminalAndNeverContainTextIdsOrUnknownTypes() {
    String secret = "private-response-access-token";
    var result =
        new PlanSseAssembler()
            .read(stream(delta(secret) + event("response.completed", EMPTY_COMPLETED)), 10000);
    String diagnostic = result.diagnostic();
    assertTrue(diagnostic.contains("terminalItems=0"));
    assertTrue(diagnostic.contains("deltaEvents=1"));
    assertTrue(diagnostic.contains("textLength=" + secret.length()));
    assertTrue(diagnostic.contains("textSource=stream"));
    assertFalse(diagnostic.contains(secret));
    assertFalse(result.toString().contains(secret));
    var normal = new PlanSseAssembler().read(stream(event("response.completed", COMPLETED)), 10000);
    assertTrue(normal.diagnostic().contains("message=1"));
    assertTrue(normal.diagnostic().contains("output_text=1"));
    assertTrue(normal.diagnostic().contains("textSource=terminal"));
    assertFalse(normal.toString().contains("resp_1"));
    assertFalse(normal.toString().contains("hello"));
  }

  @Test
  void streamedToolItemCannotHideBehindEmptyCompletedOutput() {
    String tool =
        frame(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"arguments\":\"private-arguments\"}}");
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(
                ChatGptException.class,
                () ->
                    new PlanSseAssembler()
                        .read(
                            stream(
                                delta("hello")
                                    + tool
                                    + event("response.completed", EMPTY_COMPLETED)),
                            10000))
            .code());
  }

  @Test
  void finalizedItemsAndPartsUseIndexOrderWithoutDuplicateText() {
    String item =
        frame(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello,"
                + " world!\"}]}}");
    assertEquals(
        "Hello, world!",
        new PlanSseAssembler()
            .read(
                stream(
                    delta("Hello, ")
                        + frame(
                            "{\"type\":\"response.output_text.done\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello,"
                                + " world!\"}")
                        + item
                        + event("response.completed", EMPTY_COMPLETED)),
                10000)
            .text());
    String later =
        frame(
            "{\"type\":\"response.output_text.done\",\"output_index\":1,\"content_index\":0,\"text\":\"second\"}");
    assertEquals(
        "firstsecond",
        new PlanSseAssembler()
            .read(
                stream(later + delta("first") + event("response.completed", EMPTY_COMPLETED)),
                10000)
            .text());
    var reused = new PlanSseAssembler();
    reused.read(stream(delta("private-first-request")), 10000);
    var empty = reused.read(stream(event("response.completed", EMPTY_COMPLETED)), 10000);
    assertEquals("", empty.text());
    assertTrue(empty.diagnostic().contains("deltaEvents=0"));
    assertTrue(empty.diagnostic().contains("textSource=none"));
  }

  @Test
  void streamedTextRemainsBoundedAndMalformedFieldsCannotBecomeResults() {
    String input = delta("Hello, world!") + event("response.completed", EMPTY_COMPLETED);
    int bytes = input.getBytes(StandardCharsets.UTF_8).length;
    assertEquals("Hello, world!", new PlanSseAssembler().read(stream(input), bytes).text());
    assertEquals(
        ChatGptException.Code.RECEIVE_LIMIT,
        assertThrows(
                ChatGptException.class, () -> new PlanSseAssembler().read(stream(input), bytes - 1))
            .code());
    for (String malformed :
        new String[] {
          "{\"type\":\"response.output_text.delta\",\"output_index\":-1,\"content_index\":0,\"delta\":\"secret\"}",
          "{\"type\":\"response.output_text.done\",\"output_index\":0,\"content_index\":0,\"text\":17}",
          "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"secret-unknown-type\"}}"
        }) {
      var error =
          assertThrows(
              ChatGptException.class,
              () ->
                  new PlanSseAssembler()
                      .read(
                          stream(frame(malformed) + event("response.completed", EMPTY_COMPLETED)),
                          10000));
      assertEquals(ChatGptException.Code.PROTOCOL, error.code());
      assertFalse(error.getMessage().contains("secret"));
    }
  }
}
