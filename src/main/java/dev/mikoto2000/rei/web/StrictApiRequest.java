package dev.mikoto2000.rei.web;
/** Reject unknown write fields, including unsupported project/session ownership and filesystem paths. */
public interface StrictApiRequest {
  @com.fasterxml.jackson.annotation.JsonAnySetter
  default void rejectUnknownField(String name,Object value) { throw new IllegalArgumentException("Unknown field"); }
}
