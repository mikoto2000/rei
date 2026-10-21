package dev.mikoto2000.rei.paper;

import java.net.URI;
import java.util.Optional;

/** 論文内容を用途や設定に応じて提供する窓口。 */
public interface PaperContentProvider {
  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  Optional<URI> resolve(Paper paper);
}
