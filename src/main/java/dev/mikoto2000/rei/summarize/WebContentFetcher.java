package dev.mikoto2000.rei.summarize;

import java.net.URI;

/** Web内容に必要な情報を外部から取得する。 */
public interface WebContentFetcher {

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  UrlFetch fetch(URI uri);
}
