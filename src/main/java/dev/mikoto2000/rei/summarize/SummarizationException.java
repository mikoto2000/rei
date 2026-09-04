package dev.mikoto2000.rei.summarize;

public class SummarizationException extends RuntimeException {

  private final String code;

  public SummarizationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public SummarizationException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
