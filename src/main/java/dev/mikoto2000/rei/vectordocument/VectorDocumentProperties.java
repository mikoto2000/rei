package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 検索対象文書の管理で受け渡すベクトル文書設定を表す。
 *
 * @param chunkSize 文書の分割単位大きさ
 * @param chunkOverlap 文書の分割単位Overlap
 */
@ConfigurationProperties(prefix = "rei.vector-document")
public record VectorDocumentProperties(int chunkSize, int chunkOverlap) {

  /** 受け取った情報からVectorDocumentPropertiesを作成する。 */
  public VectorDocumentProperties {
    if (chunkSize <= 0) {
      chunkSize = 512;
    }
    if (chunkOverlap < 0) {
      chunkOverlap = 0;
    }
    if (chunkOverlap >= chunkSize) {
      chunkOverlap = Math.max(0, chunkSize - 1);
    }
  }
}
