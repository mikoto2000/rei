package dev.mikoto2000.rei.doctor;

/**
 * 実行環境の診断に必要な環境診断診断を扱う。
 *
 * <p>A diagnostic returns one scoped observation; callers isolate failures between checks.
 */
@FunctionalInterface
public interface DoctorDiagnostic {
  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  DoctorResult inspect() throws Exception;
}
