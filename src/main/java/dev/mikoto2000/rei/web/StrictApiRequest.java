package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作に必要な厳格なAPI要求を扱う。
 *
 * <p>Reject unknown write fields, including unsupported project/session ownership and filesystem
 * paths.
 */
public interface StrictApiRequest {
  /** 受け付けられない条件を呼び出し元へ伝えるため、不明項目を拒否する。 */
  @com.fasterxml.jackson.annotation.JsonAnySetter
  default void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown field");
  }
}
