package dev.mikoto2000.rei.llm;

import java.io.IOException;

import dev.mikoto2000.rei.computeruse.ShowUiRequestInterceptor;
import okhttp3.Interceptor;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/** Applies ShowUI's wire format to both synchronous and streaming SDK requests. */
public final class ShowUiSdkRequestInterceptor implements Interceptor {
  @Override
  public Response intercept(Chain chain) throws IOException {
    var request = chain.request();
    var body = request.body();
    if (body == null || body.isOneShot() || body.isDuplex() || !request.url().encodedPath().endsWith("/chat/completions")) {
      return chain.proceed(request);
    }
    var buffer = new Buffer();
    body.writeTo(buffer);
    byte[] original = buffer.readByteArray();
    byte[] rewritten = ShowUiRequestInterceptor.rewriteBody(original);
    if (java.util.Arrays.equals(original, rewritten)) return chain.proceed(request);
    return chain.proceed(request.newBuilder()
        .method(request.method(), RequestBody.create(rewritten, body.contentType())).build());
  }
}
