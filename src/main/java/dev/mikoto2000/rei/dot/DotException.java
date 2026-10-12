package dev.mikoto2000.rei.dot;

public final class DotException extends IllegalArgumentException {
  public final String code;

  public DotException(String code) {
    super(code);
    this.code = code;
  }
}
