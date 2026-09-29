package dev.mikoto2000.rei.paper;

import java.net.URI;
import java.util.Optional;

public class ArxivContentProvider implements PaperContentProvider {
  public Optional<URI> resolve(Paper p) {
    String id = p.arxivId();
    if (id == null) return Optional.empty();
    if (!id.matches("(?:[0-9]{4}\\.[0-9]{4,5}|[a-z-]+/[0-9]{7})(?:v[0-9]+)?"))
      return Optional.empty();
    return Optional.of(URI.create("https://arxiv.org/pdf/" + id));
  }
}
