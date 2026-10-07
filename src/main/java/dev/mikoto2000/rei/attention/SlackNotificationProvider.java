package dev.mikoto2000.rei.attention;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;

/** Slack Web API adapter: HTTP success alone is not a delivery receipt. */
@Component
@ConditionalOnProperty(name="rei.attention.slack.enabled",havingValue="true")
@EnableConfigurationProperties(SlackNotificationProperties.class)
public class SlackNotificationProvider implements NotificationProvider,AutoCloseable {
  private final SlackNotificationProperties properties;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).maxNumberLength(64).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  public SlackNotificationProvider(SlackNotificationProperties properties){this.properties=properties;}
  @Override public String name(){return "SLACK";}
  @Override public long minimumIntervalMillis(){return 1000;}
  @Override public String destination(){return digest(name()+":"+properties.endpoint()+":"+properties.channel()+":"+digest(properties.botToken()==null?"":properties.botToken()));}
  static String digest(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  @Override public Receipt deliver(String id,String metadata) throws Exception {
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Notification delivery cancelled before send");
    if(!properties.enabled())throw new IllegalArgumentException("Slack delivery is disabled");
    UUID.fromString(id);if(metadata==null||metadata.length()>4096)throw new IllegalArgumentException("Invalid notification metadata");
    JsonNode source=mapper.readTree(metadata);var fields=new HashSet<String>();source.fieldNames().forEachRemaining(fields::add);
    if(!source.isObject()||!fields.equals(Set.of("schemaVersion","id","projectId","kind","createdAt"))||!source.path("schemaVersion").isIntegralNumber()||source.path("schemaVersion").asLong()!=1||!id.equals(source.path("id").asText())
        ||!source.path("projectId").isTextual()||!source.path("kind").asText().matches("[A-Z_]{1,64}")||source.path("projectId").asText().length()>128||!source.path("createdAt").isTextual())throw new IllegalArgumentException("Only bounded notification metadata can be sent");
    java.time.Instant.parse(source.path("createdAt").asText());
    String text="Rei "+source.path("kind").asText()+"; Project "+source.path("projectId").asText()+"; notification "+id+"; at "+source.path("createdAt").asText();
    String payload=mapper.writeValueAsString(Map.of("channel",properties.channel(),"client_msg_id",id,"text",text,"mrkdwn",false,"parse","none","unfurl_links",false,"unfurl_media",false));
    var request=HttpRequest.newBuilder(URI.create(properties.endpoint())).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+properties.botToken()).header("Content-Type","application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    var headers=new java.util.concurrent.atomic.AtomicReference<HttpResponse.ResponseInfo>();
    var pending=client.sendAsync(request,info->{headers.set(info);return new LimitedSubscriber();});
    try {
      var response=pending.get(2,TimeUnit.SECONDS);int code=response.statusCode();
      if(code==429)return rateLimited(response.headers());
      if(code>=500)return new Receipt("UNKNOWN","slack_server_outcome_unknown","",0);
      if(code<200||code>=300)return new Receipt("FAILED","http_"+code,"",0);
      JsonNode result;
      try{result=mapper.readTree(response.body());}catch(java.io.IOException error){return unknown();}
      if(result==null||!result.isObject()||!result.path("ok").isBoolean())return unknown();
      if(result.path("ok").asBoolean()) {
        String channel=result.path("channel").asText(),ts=result.path("ts").asText();
        if(!properties.channel().equals(channel)||!ts.matches("[0-9]{1,20}\\.[0-9]{1,10}"))return unknown();
        return new Receipt("SENT","slack_message_confirmed",channel+":"+ts,0);
      }
      String error=result.path("error").asText();
      if(Set.of("internal_error","fatal_error","request_timeout","service_unavailable").contains(error))return unknown();
      if(Set.of("rate_limited","ratelimited").contains(error))return new Receipt("FAILED","slack_rate_limited","",60);
      if(Set.of("invalid_auth","not_authed","token_revoked","token_expired","account_inactive","channel_not_found","not_in_channel","missing_scope","no_permission","is_archived","invalid_arguments","no_text").contains(error))return new Receipt("FAILED","slack_rejected","",0);
      return unknown();
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
    catch(ExecutionException|TimeoutException error){var received=headers.get();return received!=null&&received.statusCode()==429?rateLimited(received.headers()):unknown();}
    finally{pending.cancel(true);}
  }
  private Receipt unknown(){return new Receipt("UNKNOWN","slack_outcome_unknown","",0);}
  private Receipt rateLimited(HttpHeaders headers){int seconds=60;try{seconds=Math.max(1,Math.min(3600,Integer.parseInt(headers.firstValue("Retry-After").orElse("60"))));}catch(NumberFormatException ignored){}return new Receipt("FAILED","slack_rate_limited","",seconds);}
  private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;private long count;private boolean stopped;
    public CompletionStage<byte[]> getBody(){return delegate.getBody();}
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming){subscription=incoming;delegate.onSubscribe(incoming);}
    public void onNext(List<java.nio.ByteBuffer> buffers){if(stopped)return;for(var buffer:buffers)count+=buffer.remaining();if(count>8192){stopped=true;delegate.onError(new java.io.IOException("Slack response limit exceeded"));subscription.cancel();return;}delegate.onNext(buffers);}
    public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
    public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
  }
  @jakarta.annotation.PreDestroy @Override public void close(){client.shutdownNow();}
}
