package dev.mikoto2000.rei.application.state;

/** 状態変更の調整を継続できない操作競合の状況を伝える例外。 */
public final class OperationConflictException extends RuntimeException {}
