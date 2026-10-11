package dev.mikoto2000.rei.web;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.attention.AttentionRepository;
class ShellReportAdviceTest {
  @Test void cliHistoryUsesShellTextWhileOrdinaryWebClientsKeepJson() throws Exception {
    var repo=mock(GoalRepository.class);when(repo.history("project","goal")).thenReturn(List.of());when(repo.attempts("project","goal")).thenReturn(List.of());
    var mvc=MockMvcBuilders.standaloneSetup(new GoalController(repo,mock(GoalLoopService.class))).setControllerAdvice(new ShellReportAdvice()).build();
    mvc.perform(get("/api/v1/projects/project/goals/goal/history").header("X-Rei-Shell-Output","true")).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("[]\nAttempts: []"));
    mvc.perform(get("/api/v1/projects/project/goals/goal/history")).andExpect(jsonPath("$.history").isArray()).andExpect(jsonPath("$.attempts").isArray());
    mvc.perform(get("/api/v1/projects/project/goals").header("X-Rei-Shell-Output","true")).andExpect(jsonPath("$.text").value("[]"));
  }
  @Test void acknowledgingAttentionHasTheExactShellMessageAndOnlyOneMutation()throws Exception {
    var repo=mock(AttentionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new AttentionController(repo)).setControllerAdvice(new ShellReportAdvice()).build();
    mvc.perform(post("/api/v1/projects/project/attention/item/ack").header("X-Rei-Shell-Output","true")).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Acknowledged. This does not approve, resume or execute the task."));
    verify(repo,times(1)).acknowledge("project","item");
  }
}
