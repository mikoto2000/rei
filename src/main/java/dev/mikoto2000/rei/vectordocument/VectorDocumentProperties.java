package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.vector-document")
public record VectorDocumentProperties(
    int chunkSize,
    int chunkOverlap
) {

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
