package dev.mikoto2000.rei.vectordocument;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

@Service
public class VectorDocumentUsageService {

  private final AtomicBoolean enabled = new AtomicBoolean(true);

  public boolean isEnabled() {
    return enabled.get();
  }

  public boolean setEnabled(boolean enabled) {
    this.enabled.set(enabled);
    return this.enabled.get();
  }
}
