package dev.mikoto2000.rei.paper;

import java.net.URI;

/** 論文HTTP通信を外部の接続先へ依頼する窓口。 */
public interface PaperHttpClient {
  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  byte[] get(URI uri, String contentType, int maxBytes, PaperOperation op);
}
