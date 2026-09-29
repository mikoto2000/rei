package dev.mikoto2000.rei.paper;

import java.net.URI;
import java.util.Optional;

public interface PaperContentProvider {
  Optional<URI> resolve(Paper paper);
}
