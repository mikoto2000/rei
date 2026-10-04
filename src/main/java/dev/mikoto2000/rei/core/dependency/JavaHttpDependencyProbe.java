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
}
