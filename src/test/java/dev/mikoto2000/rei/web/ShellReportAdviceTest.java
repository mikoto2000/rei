package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.attention.AttentionRepository;
import dev.mikoto2000.rei.checkpoint.CheckpointReconciler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** シェルレポート助言の結果と状態の契約を検証するテスト。 */
class ShellReportAdviceTest {
  /** CLI向けの再開応答にも、出力購読と後続入力に必要な識別子を保持する。 */
  @Test
  void resumeShellOutputPreservesRunAndSessionWhileWebClientsKeepFullResult() throws Exception {
    var service = mock(PersistentCheckpointService.class);
    var result =
        new PersistentCheckpointService.ResumeResult(
            "task",
            "run",
            "previous",
            113,
            "target-session",
            new CheckpointReconciler.Result(
                "CONTINUE", List.of(), List.of(), List.of(), List.of(), List.of(), "continue"));
    when(service.resumeForUser("project", "task", AgentRunContext.RequestSource.WEB))
        .thenReturn(result);
    var mvc =
        MockMvcBuilders.standaloneSetup(new CheckpointController(service))
            .setControllerAdvice(new ShellReportAdvice())
            .build();
    mvc.perform(
            post("/api/v1/projects/project/checkpoints/task/resume")
                .header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.text").value(result.toString()))
        .andExpect(jsonPath("$.runId").value("run"))
        .andExpect(jsonPath("$.sessionId").value("target-session"));
    mvc.perform(post("/api/v1/projects/project/checkpoints/task/resume"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.taskId").value("task"))
        .andExpect(jsonPath("$.runId").value("run"))
        .andExpect(jsonPath("$.previousRunId").value("previous"))
        .andExpect(jsonPath("$.checkpointRevision").value(113))
        .andExpect(jsonPath("$.sessionId").value("target-session"))
        .andExpect(jsonPath("$.reconciliation.decision").value("CONTINUE"))
        .andExpect(jsonPath("$.text").doesNotExist());
  }

  /** 軽量CLI履歴について、シェルテキストを利用することを確認する。 */
  @Test
  void cliHistoryUsesShellTextWhileOrdinaryWebClientsKeepJson() throws Exception {
    var repo = mock(GoalRepository.class);
    when(repo.history("project", "goal")).thenReturn(List.of());
    when(repo.attempts("project", "goal")).thenReturn(List.of());
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoalController(repo, mock(GoalLoopService.class)))
            .setControllerAdvice(new ShellReportAdvice())
            .build();
    mvc.perform(
            get("/api/v1/projects/project/goals/goal/history").header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("[]\nAttempts: []"));
    mvc.perform(get("/api/v1/projects/project/goals/goal/history"))
        .andExpect(jsonPath("$.history").isArray())
        .andExpect(jsonPath("$.attempts").isArray());
    mvc.perform(get("/api/v1/projects/project/goals").header("X-Rei-Shell-Output", "true"))
        .andExpect(jsonPath("$.text").value("[]"));
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void acknowledgingAttentionHasTheExactShellMessageAndOnlyOneMutation() throws Exception {
    var repo = mock(AttentionRepository.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new AttentionController(repo))
            .setControllerAdvice(new ShellReportAdvice())
            .build();
    mvc.perform(
            post("/api/v1/projects/project/attention/item/ack")
                .header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.text")
                .value("Acknowledged. This does not approve, resume or execute the task."));
    verify(repo, times(1)).acknowledge("project", "item");
  }
}
