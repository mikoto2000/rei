package dev.mikoto2000.rei.dot;

/** 利用者の状態表示を継続できない状態表示の状況を伝える例外。 */
public final class DotException extends IllegalArgumentException {
  @java.io.Serial private static final long serialVersionUID = -7961545729139465899L;

  /** コード。 */
  public final String code;

  /** 指定された値や利用先を使って、DotExceptionを初期化する。 */
  public DotException(String code) {
    super(code);
    this.code = code;
  }
}
