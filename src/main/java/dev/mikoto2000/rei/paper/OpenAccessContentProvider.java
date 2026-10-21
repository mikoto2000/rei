package dev.mikoto2000.rei.paper;

import java.net.URI;
import java.util.Optional;

/** 開始アクセス内容を用途や設定に応じて提供する窓口。 */
public class OpenAccessContentProvider implements PaperContentProvider {
  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public Optional<URI> resolve(Paper p) {
    return Boolean.TRUE.equals(p.openAccess()) && p.pdfUrl() != null
        ? Optional.of(URI.create(p.pdfUrl()))
        : Optional.empty();
  }
}
