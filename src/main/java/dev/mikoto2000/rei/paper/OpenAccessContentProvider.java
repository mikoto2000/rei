package dev.mikoto2000.rei.paper;

import java.net.URI;
import java.util.Optional;

public class OpenAccessContentProvider implements PaperContentProvider {
  public Optional<URI> resolve(Paper p) {
    return Boolean.TRUE.equals(p.openAccess()) && p.pdfUrl() != null
        ? Optional.of(URI.create(p.pdfUrl()))
        : Optional.empty();
  }
}
