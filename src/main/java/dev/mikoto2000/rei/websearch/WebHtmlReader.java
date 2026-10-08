package dev.mikoto2000.rei.websearch;

import java.io.*;
import dev.mikoto2000.rei.http.*;

/** Conservative markup density guard before feeding parser chunks, with operation cancellation. */
final class WebHtmlReader extends Reader {
  private final StringReader input;
  private int markup;
  WebHtmlReader(String html, int maxCharacters) {
    if (html == null || html.length() > maxCharacters) throw new HttpFetchException(HttpFetchException.Code.EXTRACTION_LIMIT);
    input = new StringReader(html);
  }
  public int read(char[] target, int offset, int length) throws IOException {
    FetchScope.current().check(); int count = input.read(target, offset, Math.min(length, 2048));
    for (int i = 0; i < count; i++) if (target[offset + i] == '<' && ++markup > 10000)
      throw new HttpFetchException(HttpFetchException.Code.EXTRACTION_LIMIT);
    return count;
  }
  public void close() { input.close(); }
}
