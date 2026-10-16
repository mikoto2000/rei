package dev.mikoto2000.rei.core.contextbudget;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Server-owned registration metadata; result JSON cannot select its delivery policy. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ResultDelivery {
  Kind value();

  enum Kind {
    FILE_CONTENT,
    LOSSLESS_RESULT,
    SEARCH_RESULT,
    DIAGNOSTIC_LOG,
    RAW_RESULT_PAGE,
    GENERAL
  }
}
