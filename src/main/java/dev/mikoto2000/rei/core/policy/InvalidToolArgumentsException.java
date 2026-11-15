package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.toolcontract.ArgumentIssue;
import java.util.List;

/**
 * ツール利用の権限制御を継続できない不正ツール引数の状況を伝える例外。
 *
 * <p>Raised only by pure argument preflight, before approval or tool execution.
 */
public final class InvalidToolArgumentsException extends IllegalArgumentException {
  @java.io.Serial private static final long serialVersionUID = -5789073081601226960L;

  /** ツール利用の権限制御における種類の選択肢。 */
  public enum Kind {
    /** 検証。 */
    VALIDATION,
    /** モデル選択内容。 */
    MODEL_SELECTION
  }

  /** 種類。 */
  private final Kind kind;

  private final List<ArgumentIssue> issues;

  /** ツール利用の権限制御を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public InvalidToolArgumentsException(String guidance) {
    this(guidance, "arguments", guidance);
  }

  public InvalidToolArgumentsException(String guidance, String path, String requirement) {
    this(guidance, List.of(new ArgumentIssue(path, requirement)));
  }

  public InvalidToolArgumentsException(String guidance, List<ArgumentIssue> issues) {
    this(guidance, issues, Kind.VALIDATION);
  }

  public InvalidToolArgumentsException(String guidance, List<ArgumentIssue> issues, Kind kind) {
    super(guidance);
    this.kind = kind;
    this.issues = List.copyOf(issues);
  }

  /** ツール利用の権限制御で用いる種類を返す。 */
  public Kind kind() {
    return kind;
  }

  public List<ArgumentIssue> issues() {
    return issues;
  }
}
