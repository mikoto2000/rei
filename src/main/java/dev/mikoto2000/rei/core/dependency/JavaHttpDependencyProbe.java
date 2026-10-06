package dev.mikoto2000.rei.core.dependency;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** No redirects, credentials or response-body exposure; one bounded request per probe. */
@Component
public class JavaHttpDependencyProbe implements DependencyHttpProbe,AutoCloseable {
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
  @Override public DependencyObservation probe(String id,String url,int expectedStatus) {
    new DependencySpec(DependencySpec.Kind.HTTP_STATUS,url,Integer.toString(expectedStatus));
    if(Thread.currentThread().isInterrupted())throw new CancellationException("HTTP observation cancelled");
    var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending=client.sendAsync(request,HttpResponse.BodyHandlers.discarding());
    try {
      var response=pending.get(2,TimeUnit.SECONDS);boolean matches=response.statusCode()==expectedStatus;
      return new DependencyObservation(id,matches?DependencyState.COMPLETED:DependencyState.WAITING,matches?"http_status_verified":"http_status_mismatch");
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("HTTP observation cancelled");}
    catch(ExecutionException|TimeoutException error){return new DependencyObservation(id,DependencyState.WAITING,"http_unavailable");}
    finally {pending.cancel(true);}
  }
  @Override @jakarta.annotation.PreDestroy public void close(){client.shutdownNow();}
  @Override public DependencyObservation probeBody(String id,String url,int expectedStatus,String sha256) {
    var spec=new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,url,expectedStatus+":"+sha256);
    if(Thread.currentThread().isInterrupted())throw new CancellationException("HTTP observation cancelled");
    var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending=client.sendAsync(request,response->new DigestSubscriber());
    try {
      var response=pending.get(2,TimeUnit.SECONDS);
      if(response.statusCode()!=expectedStatus)return new DependencyObservation(id,DependencyState.WAITING,"http_status_mismatch");
      boolean matches=response.body().equals(spec.expected().substring(4));
      return new DependencyObservation(id,matches?DependencyState.COMPLETED:DependencyState.WAITING,matches?"http_body_digest_verified":"http_body_digest_mismatch");
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("HTTP observation cancelled");}
    catch(ExecutionException error){
      for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof BodyLimitExceeded)
        return new DependencyObservation(id,DependencyState.BLOCKED,"http_body_too_large");
      return new DependencyObservation(id,DependencyState.WAITING,"http_unavailable");
    }catch(TimeoutException error){return new DependencyObservation(id,DependencyState.WAITING,"http_unavailable");}
    finally {pending.cancel(true);}
  }
  private static final class BodyLimitExceeded extends java.io.IOException {}
  @Override public DependencyObservation probeJson(String id,String url,String expected) {
    new DependencySpec(DependencySpec.Kind.HTTP_JSON_VALUE,url,expected);var condition=HttpJsonCondition.parse(expected);
    if(Thread.currentThread().isInterrupted())throw new CancellationException("HTTP observation cancelled");
    var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending=client.sendAsync(request,response->new JsonSubscriber());
    try {
      var response=pending.get(2,TimeUnit.SECONDS);
      if(response.statusCode()!=condition.status())return new DependencyObservation(id,DependencyState.WAITING,"http_status_mismatch");
      boolean matches=condition.matches(response.body());
      return new DependencyObservation(id,matches?DependencyState.COMPLETED:DependencyState.WAITING,matches?"http_json_value_verified":"http_json_value_mismatch");
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("HTTP observation cancelled");}
    catch(ExecutionException error){
      for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof BodyLimitExceeded)return new DependencyObservation(id,DependencyState.BLOCKED,"http_body_too_large");
      return new DependencyObservation(id,DependencyState.WAITING,"http_unavailable");
    }catch(TimeoutException error){return new DependencyObservation(id,DependencyState.WAITING,"http_unavailable");}
    catch(java.io.IOException error){return new DependencyObservation(id,DependencyState.WAITING,"http_json_invalid");}
    finally {pending.cancel(true);}
  }
  private static final class JsonSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;private long count;private boolean stopped;
    public CompletionStage<byte[]> getBody(){return delegate.getBody();}
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming){subscription=incoming;delegate.onSubscribe(incoming);}
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
      if(stopped)return;
      for(var buffer:buffers)count+=buffer.remaining();
      if(count>65536){stopped=true;delegate.onError(new BodyLimitExceeded());subscription.cancel();return;}
      delegate.onNext(buffers);
    }
    public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
    public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
  }
  /** Hash streaming byte buffers; never retain or decode the response body. */
  private static final class DigestSubscriber implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final java.security.MessageDigest digest;
    private java.util.concurrent.Flow.Subscription subscription;private long count;
    DigestSubscriber(){try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public CompletionStage<String> getBody(){return result;}
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming){if(subscription!=null){incoming.cancel();return;}subscription=incoming;incoming.request(1);}
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
      if(result.isDone())return;
      for(var buffer:buffers) {
        count+=buffer.remaining();if(count>65536){result.completeExceptionally(new BodyLimitExceeded());subscription.cancel();return;}
        digest.update(buffer.asReadOnlyBuffer());
      }
      subscription.request(1);
    }
    public void onError(Throwable error){result.completeExceptionally(error);}
    public void onComplete(){result.complete(java.util.HexFormat.of().formatHex(digest.digest()));}
  }
}
