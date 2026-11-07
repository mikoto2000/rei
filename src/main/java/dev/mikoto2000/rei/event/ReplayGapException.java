package dev.mikoto2000.rei.event;

/** 実行状況の通知を継続できない再送間隔の状況を伝える例外。 */
public final class ReplayGapException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 7178930685506983535L;

  /** 既定の状態でReplayGapExceptionを初期化する。 */
  public ReplayGapException() {
    super("Required run event history is no longer retained");
  }
}
