package dev.mikoto2000.rei.subagent;

import java.util.List;

/**
 * 補助エージェントへの委譲を継続できない補助エージェント検証の状況を伝える例外。
 *
 * <p>Does not retain raw model output or parser/library exceptions containing it.
 */
public final class SubAgentValidationException extends RuntimeException {
  /** エラー一覧。 */
  private final List<ValidationError> errors;

  /** 指定された値や利用先を使って、SubAgentValidationExceptionを初期化する。 */
  public SubAgentValidationException(List<ValidationError> errors) {
    super("SubAgent structural validation failed");
    this.errors = List.copyOf(errors);
  }

  /** 補助エージェントへの委譲で用いるエラー一覧を返す。 */
  public List<ValidationError> errors() {
    return errors;
  }
}
