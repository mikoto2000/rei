package dev.mikoto2000.rei.core.contextbudget;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * モデルへ渡す文脈の管理に必要な属性を宣言する。
 *
 * <p>Server-owned registration metadata; result JSON cannot select its delivery policy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ResultDelivery {
  /** 後続の処理で使う値を返す。 */
  Kind value();

  /** Server registration for a bounded list of review pages. */
  boolean reviewPages() default false;

  /** モデルへ渡す文脈の管理における種類の選択肢。 */
  enum Kind implements dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.PolicyValue {
    /** ファイル内容。 */
    FILE_CONTENT,
    /** LOSSLESS結果。 */
    LOSSLESS_RESULT,
    /** 検索結果。 */
    SEARCH_RESULT,
    /** 診断ログ。 */
    DIAGNOSTIC_LOG,
    /** 元の結果ページ。 */
    RAW_RESULT_PAGE,
    /** 通常の結果配信方式。 */
    GENERAL;

    /** 保存する配信方式名を返す。 */
    @Override
    public String deliveryKind() {
      return name();
    }
  }
}
