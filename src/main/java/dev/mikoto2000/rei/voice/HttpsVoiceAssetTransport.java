package dev.mikoto2000.rei.voice;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.LongConsumer;

/** JVM-default certificate validation, bounded HTTPS redirects and exact-size/hash streaming. */
public final class HttpsVoiceAssetTransport implements VoiceAssetTransport,AutoCloseable {
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  public void download(VoiceModelManifest.Asset asset,Path target,Cancellation token,LongConsumer progress) throws IOException {
    URI uri=asset.url();
    for(int redirects=0;redirects<=5;redirects++) {
      token.check();VoiceModelManifest.requireHttps(uri);
      var request=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent","Rei-Voice-Model-Manager")
        .header("Accept-Encoding","identity").GET().build();
      var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
      HttpResponse<InputStream> response;
      try {response=pending.get();}
      catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();throw new InterruptedIOException("Voice request interrupted");}
      catch(ExecutionException e){throw new IOException("Voice HTTPS request failed",e.getCause());}
      token.attach(response.body());
      try {
        int code=response.statusCode();
        if(code==301||code==302||code==303||code==307||code==308) {
          String location=response.headers().firstValue("Location").orElseThrow(()->new IOException("Voice redirect lacks location"));
          URI next=uri.resolve(location);VoiceModelManifest.requireHttps(next);uri=next;
          continue;
        }
        if(code!=200)throw new IOException("Voice HTTPS status: "+code);
        long advertised=response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if(advertised>=0&&advertised!=asset.bytes())throw new IOException("Voice response size mismatch");
        writeBody(asset,response.body(),target,token,progress);return;
      } finally {response.body().close();token.detach();}
    }
    throw new IOException("Voice redirect limit exceeded");
  }
  static void writeBody(VoiceModelManifest.Asset asset,InputStream input,Path target,Cancellation token,LongConsumer progress) throws IOException {
    token.attach(input);
    try(input;var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
      long total=0;byte[] buffer=new byte[65536];int count;
      while((count=input.read(buffer))!=-1) {
        token.check();if(count==0)continue;
        if(count>asset.bytes()-total)throw new IOException("Voice body exceeds fixed size");
        output.write(buffer,0,count);total+=count;progress.accept(total);
      }
      token.check();if(total!=asset.bytes())throw new IOException("Voice body is incomplete");
    } finally {token.detach();}
    VoiceModelManifest.verifyAsset(asset,target);
  }
  public void close(){client.shutdownNow();}
}