package dev.mikoto2000.rei.paper;

public class PaperException extends RuntimeException {
  public enum Code {
    SEARCH_FAILED,
    PAPER_NOT_FOUND,
    CONTENT_NOT_AVAILABLE,
    PDF_DOWNLOAD_FAILED,
    PDF_PARSE_FAILED,
    SUMMARY_FAILED,
    TRANSLATION_FAILED,
    INVALID_QUERY,
    INVALID_PAPER_REFERENCE,
    PROVIDER_RATE_LIMITED,
    PROVIDER_TIMEOUT,
    LIBRARY_STORAGE_FAILED,
    LIBRARY_READ_FAILED,
    LIBRARY_DELETE_FAILED,
    ARTIFACT_NOT_FOUND
  }

  private final Code code;

  public PaperException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public PaperException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
