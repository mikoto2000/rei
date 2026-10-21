package dev.mikoto2000.rei.core.policy;

/**
 * ツール利用の権限制御を継続できない不正ツール引数の状況を伝える例外。
 *
 * <p>Raised only by pure argument preflight, before approval or tool execution.
 */
public final class InvalidToolArgumentsException extends IllegalArgumentException {
  /** ツール利用の権限制御における種類の選択肢。 */
  public enum Kind {
    /** 検証。 */
    VALIDATION,
    /** モデル選択内容。 */
    MODEL_SELECTION
  }

  /** 種類。 */
  private final Kind kind;

  /** ツール利用の権限制御を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public InvalidToolArgumentsException(String guidance) {
    this(guidance, Kind.VALIDATION);
  }

  /** 指定された値や利用先を使って、InvalidToolArgumentsExceptionを初期化する。 */
  public InvalidToolArgumentsException(String guidance, Kind kind) {
    super(guidance);
    this.kind = kind;
  }

  /** ツール利用の権限制御で用いる種類を返す。 */
  public Kind kind() {
    return kind;
  }
}
