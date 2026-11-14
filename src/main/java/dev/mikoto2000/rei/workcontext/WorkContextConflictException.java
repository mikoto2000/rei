package dev.mikoto2000.rei.workcontext;

/** Work Context の更新が別の更新と競合したことを示す。 */
public final class WorkContextConflictException extends java.util.ConcurrentModificationException {
  /** 競合の説明を指定する。 */
  WorkContextConflictException(String message) {
    super(message);
  }
}
