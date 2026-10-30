package dev.mikoto2000.rei.websearch;

/** OpenAI 検索の固定診断コードを、外部の応答本文を含めずに保持する。 */
final class OpenAiWebSearchException extends IllegalStateException
    implements dev.mikoto2000.rei.toolcontract.ExternalReadFailure {
  private final String code;

  OpenAiWebSearchException(String code) {
    super("OpenAI web search: " + code);
    this.code = code;
  }

  @Override
  public String diagnosticCode() {
    return code;
  }
}
