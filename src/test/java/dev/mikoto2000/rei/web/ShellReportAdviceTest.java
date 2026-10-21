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
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** シェルレポート助言の結果と状態の契約を検証するテスト。 */
class ShellReportAdviceTest {
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
