package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;

/** SdkSlack通信経路の結果と状態の契約を検証するテスト。 */
class SdkSlackTransportTest {
  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private ChatPostMessageResponse response(boolean ok, String error) {
    var r = new ChatPostMessageResponse();
    r.setOk(ok);
    r.setError(error);
    r.setChannel("D");
    r.setTs("9.1");
    return r;
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sendsOnlyOnePostToTheOriginalThread() throws Exception {
    var methods = mock(MethodsClient.class);
    when(methods.chatPostMessage(any(ChatPostMessageRequest.class)))
        .thenReturn(response(true, null));
    var transport = new SdkSlackTransport(methods, "T");
    var row = new SlackStore.Outgoing("id", "T", "D", "1.0", "answer");
    assertEquals("SENT", transport.send(row).state());
    verify(methods)
        .chatPostMessage(
            argThat(
                (ChatPostMessageRequest r) ->
                    r.getThreadTs().equals("1.0")
                        && r.getChannel().equals("D")
                        && !r.isUnfurlLinks()));
    assertEquals(
        "FAILED",
        transport.send(new SlackStore.Outgoing("bad", "other", "D", "1.0", "answer")).state());
    verifyNoMoreInteractions(methods);
  }

  /** Slackとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void uncertainFailureIsNotAutomaticallyRetried() throws Exception {
    var methods = mock(MethodsClient.class);
    when(methods.chatPostMessage(any(ChatPostMessageRequest.class)))
        .thenThrow(new java.io.IOException("possibly sent"));
    assertEquals(
        "UNKNOWN",
        new SdkSlackTransport(methods, "T")
            .send(new SlackStore.Outgoing("id", "T", "D", "1.0", "answer"))
            .state());
    verify(methods, times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void explicitRateLimitIsRetryableButServerUncertaintyIsNot() throws Exception {
    var methods = mock(MethodsClient.class);
    when(methods.chatPostMessage(any(ChatPostMessageRequest.class)))
        .thenReturn(response(false, "ratelimited"), response(false, "internal_error"));
    var transport = new SdkSlackTransport(methods, "T");
    var row = new SlackStore.Outgoing("id", "T", "D", "1.0", "answer");
    assertEquals("RETRY", transport.send(row).state());
    assertEquals("UNKNOWN", transport.send(row).state());
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void http429HonorsRetryAfter() throws Exception {
    var methods = mock(MethodsClient.class);
    for (String delay : java.util.List.of("7", "7200")) {
      var response =
          new okhttp3.Response.Builder()
              .request(
                  new okhttp3.Request.Builder()
                      .url("https://slack.com/api/chat.postMessage")
                      .build())
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(429)
              .message("rate limited")
              .header("Retry-After", delay)
              .build();
      when(methods.chatPostMessage(any(ChatPostMessageRequest.class)))
          .thenThrow(new com.slack.api.methods.SlackApiException(response, "{}"));
      var result =
          new SdkSlackTransport(methods, "T")
              .send(new SlackStore.Outgoing("id", "T", "D", "1.0", "answer"));
      assertEquals("RETRY", result.state());
      assertEquals(Integer.parseInt(delay), result.retryAfterSeconds());
    }
  }
}
