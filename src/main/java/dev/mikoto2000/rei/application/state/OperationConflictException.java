package dev.mikoto2000.rei.application.state;

/** 状態変更の調整を継続できない操作競合の状況を伝える例外。 */
public final class OperationConflictException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 7681358505007801103L;
}
