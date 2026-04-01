package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.vector-document")
public record VectorDocumentProperties(
    int chunkSize
) {

  public VectorDocumentProperties {
    if (chunkSize <= 0) {
      chunkSize = 512;
    }
  }
}
