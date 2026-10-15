package dev.mikoto2000.rei.core.contextbudget;

/**
 * Raw pages are already text envelopes: adding JSON string quoting would hide their provenance
 * headers.
 */
public final class RawResultPageTextConverter
    implements org.springframework.ai.tool.execution.ToolCallResultConverter {
  @Override
  public String convert(Object result, java.lang.reflect.Type returnType) {
    if (!(result instanceof String text))
      throw new RawResultPageException(RawResultPageException.Reason.INVALID_PROVENANCE);
    return RawResultPage.parse(text).text();
  }
}
