package dev.mikoto2000.rei.doctor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/**
 * 実行環境の診断に必要な環境診断診断を扱う。
 *
 * <p>A diagnostic returns one scoped observation; callers isolate failures between checks.
 */
@FunctionalInterface
public interface DoctorDiagnostic {
  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Diagnostic implementations may probe different subsystems and report their checked failures.")
  DoctorResult inspect() throws Exception;
}
