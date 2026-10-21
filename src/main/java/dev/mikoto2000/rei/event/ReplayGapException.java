package dev.mikoto2000.rei.event;

/** 実行状況の通知を継続できない再送間隔の状況を伝える例外。 */
public final class ReplayGapException extends RuntimeException {
  /** 既定の状態でReplayGapExceptionを初期化する。 */
  public ReplayGapException() {
    super("Required run event history is no longer retained");
  }
}
