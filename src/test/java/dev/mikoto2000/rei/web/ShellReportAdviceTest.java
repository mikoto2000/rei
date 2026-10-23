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
  /** 承認操作ではCLIの所有確認と表示に必要な構造化フィールドを保持する。 */
  @Test
  void approvalShellOutputPreservesOwnershipAcrossListShowAndContinue() throws Exception {
    var repository = mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    var continuations = mock(dev.mikoto2000.rei.core.policy.ApprovalContinuationService.class);
    var request =
        new dev.mikoto2000.rei.core.policy.ToolApprovalRepository.Request(
            "approval",
            "project",
            "session",
            "run",
            "writeMultiFile",
            "preview",
            "PENDING",
            java.time.Instant.parse("2030-01-01T00:00:00Z"));
    when(repository.get("project", "approval")).thenReturn(request);
    when(continuations.list("project")).thenReturn(List.of(request));
    when(continuations.state("approval")).thenReturn(java.util.Optional.of("WAITING_APPROVAL"));
    when(continuations.decide("project", "approval", true, true)).thenReturn(request);
    var controller = new ApprovalController(repository);
    controller.setContinuations(continuations);
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ShellReportAdvice())
            .build();
    String base = "/api/v1/projects/project/approvals";
    mvc.perform(get(base).header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("approval"))
        .andExpect(jsonPath("$[0].projectId").value("project"));
    mvc.perform(get(base + "/approval").header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value("project"))
        .andExpect(jsonPath("$.sessionId").value("session"))
        .andExpect(jsonPath("$.runId").value("run"))
        .andExpect(jsonPath("$.argumentsPreview").value("preview"));
    mvc.perform(
            post(base + "/approval/decision")
                .header("X-Rei-Shell-Output", "true")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"continueRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("approval"))
        .andExpect(jsonPath("$.projectId").value("project"))
        .andExpect(jsonPath("$.sessionId").value("session"))
        .andExpect(jsonPath("$.runId").value("run"));
    mvc.perform(get(base + "/approval/continuation").header("X-Rei-Shell-Output", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value("approval"))
        .andExpect(jsonPath("$.runId").value("run"))
        .andExpect(jsonPath("$.state").value("WAITING_APPROVAL"));
    verify(continuations, times(1)).decide("project", "approval", true, true);
  }

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
