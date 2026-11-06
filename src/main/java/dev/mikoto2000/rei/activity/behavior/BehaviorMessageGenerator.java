package dev.mikoto2000.rei.activity.behavior;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 作業習慣メッセージの内容を生成する。 */
@FunctionalInterface
public interface BehaviorMessageGenerator {
  /** 指定された条件に従って、依頼された内容を生成する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "The configured message generator can report checked failures for the behavior service to handle.")
  String generate(BehaviorNotification notification) throws Exception;
}
