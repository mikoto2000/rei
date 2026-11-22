package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.runmanagement.CheckpointCandidate;
import dev.mikoto2000.rei.runmanagement.RunManagement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ListingControllersTest {
  @Test
  void candidateEndpointIsAuthenticatedAndPassesExplicitScope() throws Exception {
    var service = mock(RunManagement.class);
    when(service.candidates(null, "failed"))
        .thenReturn(
            List.of(
                new CheckpointCandidate(
                    "two", "Two", "task", "FAILED", "request", 1, Instant.EPOCH)));
    when(service.candidates(null, "faild"))
        .thenThrow(new IllegalArgumentException("Invalid status"));
    when(service.candidates("foreign", null))
        .thenThrow(new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new CheckpointCandidatesController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new ApiKeyAuthenticationFilter(new ApiKeyProperties("fixture")))
            .build();
    mvc.perform(get("/api/v1/checkpoints/candidates")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
    mvc.perform(
            get("/api/v1/checkpoints/candidates")
                .param("status", "failed")
                .header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].projectId").value("two"));
    mvc.perform(
            get("/api/v1/checkpoints/candidates")
                .param("status", "faild")
                .header("Authorization", "Bearer fixture"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/checkpoints/candidates")
                .param("projectId", "foreign")
                .header("Authorization", "Bearer fixture"))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/checkpoints/candidates")
                .param("projectId", "one")
                .header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk());
    verify(service).candidates("one", null);
  }

  @Test
  void historyEndpointPassesScopeStatusAndLimitToTheSharedService() throws Exception {
    var service = mock(RunManagement.class);
    when(service.history(null, "completed", 3)).thenReturn(List.of());
    var mvc = MockMvcBuilders.standaloneSetup(new RunManagementController(service)).build();
    mvc.perform(
            get("/api/v1/run-management/history").param("status", "completed").param("limit", "3"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    verify(service).history(null, "completed", 3);
  }
}
