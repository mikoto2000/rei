package dev.mikoto2000.rei.core.dependency;

/**
 * 外部条件の待機で受け渡す依存条件観測内容を表す。
 *
 * @param id 識別子
 * @param state 状態
 * @param detail 詳細
 */
public record DependencyObservation(String id, DependencyState state, String detail) {
  /** 受け取った情報からDependencyObservationを作成する。 */
  public DependencyObservation {
    if (id == null || id.isBlank() || state == null)
      throw new IllegalArgumentException("Dependency identity and state required");
  }
}
