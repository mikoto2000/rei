package dev.mikoto2000.rei.attention;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/** One application send attempt, no redirects and a bounded wall-clock wait. Response bodies are discarded. */
@Component
public class JdkAttentionSender {
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
  public int send(AttentionDeliveryProperties properties,String id,String json) throws Exception {
    if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("Notification metadata is too large");
    var builder=HttpRequest.newBuilder(URI.create(properties.endpoint())).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header("Idempotency-Key",id).POST(HttpRequest.BodyPublishers.ofString(json));
    if(properties.bearerToken()!=null&&!properties.bearerToken().isEmpty())builder.header("Authorization","Bearer "+properties.bearerToken());
    var future=client.sendAsync(builder.build(),HttpResponse.BodyHandlers.discarding());
    try{return future.get(2,TimeUnit.SECONDS).statusCode();}finally{future.cancel(true);}
  }
  @PreDestroy public void close(){client.shutdownNow();}
}
