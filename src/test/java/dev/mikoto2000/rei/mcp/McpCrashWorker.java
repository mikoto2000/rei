package dev.mikoto2000.rei.mcp;

import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** 検証条件を再現するためのMCPCrash実行の担当。 */
public final class McpCrashWorker {
  /** 検証用に、mCPによる機能の提供の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Clock clock = Clock.systemUTC();
    var gate = new StorageMigrationCoordinator(root);
    gate.prepare();
    var admissions = new ConversationAdmissionStore(root, clock);
    var turns = new SqliteConversationTurnStore(root);
    var store =
        new McpRequestStore(
            root, admissions, turns, clock, Duration.ofDays(30), Duration.ofDays(90));
    for (String id : List.of("completed", "pending")) {
      var context =
          new AgentRunContext(
              id,
              "session-" + id,
              root,
              "project",
              AgentRunContext.RequestSource.MCP,
              AgentRunContext.Mode.READ_ONLY);
      admissions.accept(
          new SessionMetadata(
              context.conversationId(), "project", "mcp", clock.instant(), clock.instant()),
          context,
          null,
          null,
          new McpAdmission(
              ConversationAdmissionStore.hash(id),
              "a".repeat(64),
              Duration.ofDays(90),
              0,
              List.of()));
      if (id.equals("pending")) {
        admissions.startExecution(context);
        admissions.transition(id, RunStatus.RUNNING);
      }
      if (id.equals("completed")) {
        turns.start(context, "request", clock.instant());
        turns.finish(context, ConversationTurnStore.Status.COMPLETED, "immutable saved result");
        admissions.transition(id, RunStatus.COMPLETED);
        admissions.release(id);
        store.snapshot(store.byRun(id).orElseThrow());
      }
    }
    System.out.println("READY");
    System.out.flush();
    System.in.read();
  }
}
