package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HttpFetchException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Web上の情報の調査に必要なWebHTML読み取り元を扱う。
 *
 * <p>Conservative markup density guard before feeding parser chunks, with operation cancellation.
 */
final class WebHtmlReader extends Reader {
  /** 入力。 */
  private final StringReader input;

  /** 除去する装飾の位置。 */
  private int markup;

  /** 指定された値や利用先を使って、WebHtmlReaderを初期化する。 */
  WebHtmlReader(String html, int maxCharacters) {
    if (html == null || html.length() > maxCharacters)
      throw new HttpFetchException(HttpFetchException.Code.EXTRACTION_LIMIT);
    input = new StringReader(html);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public int read(char[] target, int offset, int length) throws IOException {
    FetchScope.current().check();
    int count = input.read(target, offset, Math.min(length, 2048));
    for (int i = 0; i < count; i++)
      if (target[offset + i] == '<' && ++markup > 10000)
        throw new HttpFetchException(HttpFetchException.Code.EXTRACTION_LIMIT);
    return count;
  }

  /** Web上の情報の調査で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    input.close();
  }
}
