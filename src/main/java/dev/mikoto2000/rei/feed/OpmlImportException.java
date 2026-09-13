package dev.mikoto2000.rei.feed;

/** A file-level import error safe to present in the shell. */
public class OpmlImportException extends RuntimeException {
  public OpmlImportException(String message) {
    super(message);
  }

  public OpmlImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
