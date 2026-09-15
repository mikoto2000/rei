package dev.mikoto2000.rei.application.run;

public final class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException(String resource) { super(resource + " not found"); }
}
