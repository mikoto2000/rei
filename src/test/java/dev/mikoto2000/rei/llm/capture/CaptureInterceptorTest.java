package dev.mikoto2000.rei.llm.capture;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
class CaptureInterceptorTest {
  @Test void oversizedSingleWriteIsRejectedBeforeAnyCaptureBuffering() throws Exception {
    var bounded=new BoundedBodySink();var huge=new byte[2*CaptureStore.BODY_LIMIT];
    assertThatThrownBy(()->bounded.sink().write(huge)).isInstanceOf(BoundedBodySink.TooLarge.class);
    assertThat(bounded.bytes()).isEmpty();
  }
  @Test void failedCaptureAndDeletedRunDoNotInterfereWithProceed() throws Exception {
    var store=new CaptureStore();store.reserve("c","v");store.accept("c","v","s","r");var logical=store.logicalCall("r",null);var writes=new AtomicInteger();
    var body=new RequestBody(){public MediaType contentType(){return null;}public long contentLength(){return 1;}
      public void writeTo(okio.BufferedSink sink) throws IOException{writes.incrementAndGet();throw new IOException("private body must not leak");}};
    var client=new OkHttpClient.Builder().addInterceptor(new CaptureInterceptor(store,logical)).addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("",null)).build()).build();
    try{
      var request=new Request.Builder().url("http://example.invalid/chat/completions").post(body).build();
      try(var response=client.newCall(request).execute()){assertThat(response.code()).isEqualTo(200);}
      assertThat(store.attempts("r").getFirst().captureState()).isEqualTo("CAPTURE_FAILED");
      store.clear();try(var response=client.newCall(request).execute()){assertThat(response.code()).isEqualTo(200);}
      assertThat(writes.get()).isEqualTo(1);assertThat(store.sessions()).isEmpty();
    }finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
  }
  @Test void originalMatchesServerAndHttpStateAndOffDoesNotReadBody() throws Exception {
    var store=new CaptureStore();store.reserve("c","v");store.accept("c","v","s","r");
    var call=store.logicalCall("r",null);var received=new java.util.concurrent.atomic.AtomicReference<byte[]>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/chat/completions",e->{received.set(e.getRequestBody().readAllBytes());e.sendResponseHeaders(200,0);e.close();});server.start();
    var client=new OkHttpClient.Builder().addInterceptor(new CaptureInterceptor(store,call)).build();
    try {
      byte[] bytes="{\"model\":\"test\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
      try(var response=client.newCall(new Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions").post(RequestBody.create(bytes,MediaType.get("application/json"))).build()).execute()){}
      var attempt=store.attempts("r").getFirst();assertThat(attempt.sendState()).isEqualTo("HTTP_RESPONSE");assertThat(attempt.httpStatus()).isEqualTo(200);
      assertThat(store.body(attempt.attemptId())).isEqualTo(bytes).isEqualTo(received.get());
    }finally{server.stop(0);client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
  }
  @Test void oneShotDuplexUnknownAndTooLargeBodiesAreNotConsumed() throws Exception {
    for(String kind:java.util.List.of("one-shot","duplex","unknown","large")){
      var store=new CaptureStore();store.reserve("c","v");store.accept("c","v","s","r");var writes=new AtomicInteger();
      var body=new RequestBody(){public MediaType contentType(){return null;}public boolean isOneShot(){return kind.equals("one-shot");}
        public boolean isDuplex(){return kind.equals("duplex");}public long contentLength(){return kind.equals("unknown")?-1:kind.equals("large")?CaptureStore.BODY_LIMIT+1:1;}
        public void writeTo(okio.BufferedSink sink) throws IOException{writes.incrementAndGet();sink.writeByte(1);}};
      var client=new OkHttpClient.Builder().addInterceptor(new CaptureInterceptor(store,store.logicalCall("r",null)))
          .addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("",null)).build()).build();
      try(var response=client.newCall(new Request.Builder().url("http://example.invalid/chat/completions").post(body).build()).execute()){}
      assertThat(writes.get()).isZero();assertThat(store.attempts("r").getFirst().captureState()).startsWith("SKIPPED_");
      assertThatThrownBy(()->store.body(store.attempts("r").getFirst().attemptId())).isInstanceOf(IllegalArgumentException.class);
      client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();
    }
  }
}
