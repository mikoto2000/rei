package dev.mikoto2000.rei.llm;

import java.io.IOException;

/**
 * Byte bounds at the final HTTP boundary; payload bytes are preserved without interpreting JSON.
 */
final class PinnedWireLimits {
  private PinnedWireLimits() {}

  static okhttp3.RequestBody request(okhttp3.RequestBody body, long limit) throws IOException {
    if (body.contentLength() > limit)
      throw new ResponsesTransportException(ResponsesTransportException.Category.REQUEST_LIMIT);
    var bytes = new okio.Buffer();
    var bounded =
        okio.Okio.buffer(
            new okio.ForwardingSink(bytes) {
              private long written;

              @Override
              public void write(okio.Buffer source, long byteCount) throws IOException {
                if (byteCount > limit - written)
                  throw new ResponsesTransportException(
                      ResponsesTransportException.Category.REQUEST_LIMIT);
                super.write(source, byteCount);
                written += byteCount;
              }
            });
    body.writeTo(bounded);
    bounded.flush();
    return okhttp3.RequestBody.create(bytes.readByteArray(), body.contentType());
  }

  static okhttp3.Response response(okhttp3.Response response, long limit) {
    var body = response.body();
    if (body == null) return response;
    if (body.contentLength() > limit) {
      response.close();
      throw new ResponsesTransportException(ResponsesTransportException.Category.RESPONSE_LIMIT);
    }
    var source =
        okio.Okio.buffer(
            new okio.ForwardingSource(body.source()) {
              private long received;

              @Override
              public long read(okio.Buffer sink, long byteCount) throws IOException {
                long count = super.read(sink, Math.min(byteCount, limit - received + 1));
                if (count > 0) {
                  received += count;
                  if (received > limit) {
                    body.close();
                    throw new ResponsesTransportException(
                        ResponsesTransportException.Category.RESPONSE_LIMIT);
                  }
                }
                return count;
              }
            });
    var bounded =
        new okhttp3.ResponseBody() {
          @Override
          public okhttp3.MediaType contentType() {
            return body.contentType();
          }

          @Override
          public long contentLength() {
            return body.contentLength();
          }

          @Override
          public okio.BufferedSource source() {
            return source;
          }
        };
    return response.newBuilder().body(bounded).build();
  }
}
