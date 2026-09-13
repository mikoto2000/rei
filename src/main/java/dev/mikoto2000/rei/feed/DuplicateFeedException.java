package dev.mikoto2000.rei.feed;

/** Allows callers to distinguish duplicates from other registration failures. */
public class DuplicateFeedException extends IllegalArgumentException {
  public DuplicateFeedException() {
    super("同じフィード URL は登録できません");
  }
}
