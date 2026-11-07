package dev.mikoto2000.rei.subagent;

import java.util.List;

/**
 * 補助エージェントへの委譲を継続できない補助エージェント検証の状況を伝える例外。
 *
 * <p>Does not retain raw model output or parser/library exceptions containing it.
 */
public final class SubAgentValidationException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 6047196781046663958L;

  @java.io.Serial
  private static final java.io.ObjectStreamField[] serialPersistentFields = {
    new java.io.ObjectStreamField("errors", List.class)
  };

  private transient List<ValidationError> errors;

  @java.io.Serial
  private void writeObject(java.io.ObjectOutputStream output) throws java.io.IOException {
    var fields = output.putFields();
    fields.put("errors", errors);
    output.writeFields();
  }

  @java.io.Serial
  private void readObject(java.io.ObjectInputStream input)
      throws java.io.IOException, ClassNotFoundException {
    Object stored = input.readFields().get("errors", null);
    if (!(stored instanceof List<?> list)
        || list.stream().anyMatch(error -> !(error instanceof ValidationError)))
      throw new java.io.InvalidObjectException("Invalid serialized diagnostics");
    errors = List.copyOf(list.stream().map(ValidationError.class::cast).toList());
  }

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
