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
                stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"),
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
}
