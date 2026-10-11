package dev.mikoto2000.rei.slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SdkSlackTransportTest {
  private ChatPostMessageResponse response(boolean ok,String error) {var r=new ChatPostMessageResponse();r.setOk(ok);r.setError(error);r.setChannel("D");r.setTs("9.1");return r;}
  @Test void sendsOnlyOnePostToTheOriginalThread() throws Exception {
    var methods=mock(MethodsClient.class);
    when(methods.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(true,null));
    var transport=new SdkSlackTransport(methods,"T");
    var row=new SlackStore.Outgoing("id","T","D","1.0","answer");assertEquals("SENT",transport.send(row).state());
    verify(methods).chatPostMessage(argThat((ChatPostMessageRequest r)->r.getThreadTs().equals("1.0")&&r.getChannel().equals("D")&&!r.isUnfurlLinks()));
    assertEquals("FAILED",transport.send(new SlackStore.Outgoing("bad","other","D","1.0","answer")).state());
    verifyNoMoreInteractions(methods);
  }
  @Test void uncertainFailureIsNotAutomaticallyRetried() throws Exception {
    var methods=mock(MethodsClient.class);when(methods.chatPostMessage(any(ChatPostMessageRequest.class))).thenThrow(new java.io.IOException("possibly sent"));
    assertEquals("UNKNOWN",new SdkSlackTransport(methods,"T").send(new SlackStore.Outgoing("id","T","D","1.0","answer")).state());
    verify(methods,times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
  }
  @Test void explicitRateLimitIsRetryableButServerUncertaintyIsNot() throws Exception {
    var methods=mock(MethodsClient.class);
    when(methods.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(false,"ratelimited"),response(false,"internal_error"));
    var transport=new SdkSlackTransport(methods,"T");var row=new SlackStore.Outgoing("id","T","D","1.0","answer");
    assertEquals("RETRY",transport.send(row).state());assertEquals("UNKNOWN",transport.send(row).state());
  }
}
