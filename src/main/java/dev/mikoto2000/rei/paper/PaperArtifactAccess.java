package dev.mikoto2000.rei.paper;

import java.util.Optional;

/** Operations for accessing a paper's cached content and metadata. */
final class PaperArtifactAccess {
  private PaperArtifactAccess() {}

  @FunctionalInterface
  interface Read {
    Optional<byte[]> read(String paperId, String kind, String extension);
  }

  @FunctionalInterface
  interface Write {
    void write(
        String paperId, String kind, String extension, byte[] bytes, PaperOperation operation);
  }

  @FunctionalInterface
  interface FindMetadata {
    Optional<String> find(String paperId, String kind, String key);
  }

  @FunctionalInterface
  interface SaveMetadata {
    void save(String paperId, String kind, String key, String content, PaperOperation operation);
  }
}
