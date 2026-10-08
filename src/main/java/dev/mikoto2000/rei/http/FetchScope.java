package dev.mikoto2000.rei.http;

import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

/** Capture explicitly before crossing executor boundaries. */
public final class FetchScope implements AutoCloseable {
  private static final ThreadLocal<FetchOperation> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<HostAdmission> ADMISSION = new ThreadLocal<>();
  private final FetchOperation previous;
  private final HostAdmission previousAdmission;
  private FetchScope(FetchOperation operation, HostAdmission admission) {
    previous = CURRENT.get(); previousAdmission = ADMISSION.get(); CURRENT.set(operation);
    if (admission == null) ADMISSION.remove(); else ADMISSION.set(admission);
  }
  public static FetchOperation current() { var operation = CURRENT.get(); return operation == null ? FetchOperation.active() : operation; }
  public static FetchScope enter(FetchOperation operation) { return new FetchScope(operation, ADMISSION.get()); }
  public static FetchScope enter(FetchOperation operation, HostAdmission admission) { return new FetchScope(operation, admission); }
  public static HostAdmission.Lease acquireConnection(java.net.URI uri, FetchOperation operation) {
    var admission = ADMISSION.get(); return admission == null ? HostAdmission.NONE : admission.acquire(uri.getHost(), operation);
  }
  public static FetchScope enter(ToolContext context) {
    Object execution = context == null ? null : context.getContext().get(RunExecutionContext.KEY);
    return enter(execution instanceof RunExecutionContext run
        ? new FetchOperation(run::checkActive, Long.MAX_VALUE) : current());
  }
  public void close() {
    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    if (previousAdmission == null) ADMISSION.remove(); else ADMISSION.set(previousAdmission);
  }
}
