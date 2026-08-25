package dev.mikoto2000.rei.skills;

import java.util.concurrent.atomic.AtomicInteger;

/** Agent Run ごとに所有される Skill routing の識別 context。 */
public final class SkillRoutingRunContext {
  private final String runId;
  private final AtomicInteger invocation = new AtomicInteger();

  public SkillRoutingRunContext(String runId) {
    this.runId = runId;
  }

  public String runId() {
    return runId;
  }

  public int nextInvocation() {
    return invocation.incrementAndGet();
  }
}
