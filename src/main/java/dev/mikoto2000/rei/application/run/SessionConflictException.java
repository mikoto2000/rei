package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない会話競合の状況を伝える例外。 */
public final class SessionConflictException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = -2949286078306634531L;

  /** 既定の状態でSessionConflictExceptionを初期化する。 */
  public SessionConflictException() {
    super("Session belongs to another project");
  }
}
