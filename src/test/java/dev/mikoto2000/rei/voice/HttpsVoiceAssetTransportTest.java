package dev.mikoto2000.rei.voice;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class HttpsVoiceAssetTransportTest {
  @TempDir Path root;
  final byte[] content="fixed asset".getBytes();
  VoiceModelManifest.Asset asset() throws Exception {
    return new VoiceModelManifest.Asset("asset",URI.create("https://example.org/fixed"),content.length,
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),"MIT");
  }
  @Test void exactContentPassesSizeAndHashChecks() throws Exception {
    var counts=new ArrayList<Long>();
    HttpsVoiceAssetTransport.writeBody(asset(),new ByteArrayInputStream(content),root.resolve("asset"),new VoiceAssetTransport.Cancellation(),counts::add);
    assertThat(Files.readAllBytes(root.resolve("asset"))).isEqualTo(content);assertThat(counts.getLast()).isEqualTo((long)content.length);
  }
  @Test void refusesOversizeShortOrSameSizeCorruptBody() throws Exception {
    for(byte[] invalid:List.of(new byte[content.length+1],new byte[content.length-1],new byte[content.length])) {
      Path target=root.resolve(UUID.randomUUID().toString());
      assertThatThrownBy(()->HttpsVoiceAssetTransport.writeBody(asset(),new ByteArrayInputStream(invalid),target,new VoiceAssetTransport.Cancellation(),n->{})).isInstanceOf(IOException.class);
      if(Files.exists(target))assertThat(Files.size(target)).isLessThanOrEqualTo((long)content.length);
    }
  }
  @Test void cancellationClosesBlockedBodyAndPreventsReadyFile() throws Exception {
    var entered=new CountDownLatch(1);var closed=new CountDownLatch(1);var token=new VoiceAssetTransport.Cancellation();
    var input=new InputStream() {
      public int read(){throw new UnsupportedOperationException();}
      public int read(byte[] b,int offset,int length) throws IOException {
        entered.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}return -1;
      }
      public void close(){closed.countDown();}
    };
    try(var executor=Executors.newSingleThreadExecutor()) {
      var future=executor.submit(()->{try{HttpsVoiceAssetTransport.writeBody(asset(),input,root.resolve("asset"),token,n->{});return "unexpected";}catch(Exception e){return e.getClass().getSimpleName();}});
      assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();token.cancel();
      assertThat(future.get(2,TimeUnit.SECONDS)).isEqualTo("InterruptedIOException");assertThat(closed.getCount()).isZero();
    } finally {token.cancel();}
  }
  @Test void redirectCannotDowngradeHttpsOrCarryCredentials() {
    for(String invalid:List.of("http://example.org/file","file:///tmp/model","https://user:secret@example.org/file","https://example.org/file#fragment"))
      assertThatThrownBy(()->VoiceModelManifest.requireHttps(URI.create(invalid))).isInstanceOf(IllegalArgumentException.class);
  }
}