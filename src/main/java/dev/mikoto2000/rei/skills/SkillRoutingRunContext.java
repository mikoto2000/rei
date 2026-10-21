package dev.mikoto2000.rei.skills;

import java.util.concurrent.atomic.AtomicInteger;

/** Agent Run ごとに所有される Skill routing の識別 context。 */
public final class SkillRoutingRunContext {
  /** 実行の識別子。 */
  private final String runId;

  /** モデル呼び出しの回数または予算。 */
  private final AtomicInteger invocation = new AtomicInteger();

  /** 指定された値や利用先を使って、SkillRoutingRunContextを初期化する。 */
  public SkillRoutingRunContext(String runId) {
    this.runId = runId;
  }

  /** 依頼された操作を進めるため、識別子を実行する。 */
  public String runId() {
    return runId;
  }

  /** スキルの利用で用いる次Invocationを返す。 */
  public int nextInvocation() {
    return invocation.incrementAndGet();
  }
}
