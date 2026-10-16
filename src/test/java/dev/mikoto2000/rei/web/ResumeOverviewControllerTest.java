package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.checkpoint.ResumeOverview;
import dev.mikoto2000.rei.checkpoint.ResumeOverviewService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ResumeOverviewControllerTest {
  @Test
  void bearerProtectionAndReadOnlyDtoMatchExistingApiPolicy() throws Exception {
    var service = mock(ResumeOverviewService.class);
    when(service.enabled()).thenReturn(true);
    when(service.counts()).thenReturn(List.of(new ResumeOverview.ProjectCount("A", "A", 2)));
    var candidate =
        new ResumeOverview.Candidate(
            "task",
            3,
            3,
            Instant.EPOCH,
            "INTERRUPTED",
            "password=secret",
            "未記録",
            "test",
            "要照合",
            false);
    when(service.overview("A"))
        .thenReturn(new ResumeOverview("A", "A", 2, candidate, "fingerprint"));
    when(service.overview("foreign"))
        .thenThrow(new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new ResumeOverviewController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new ApiKeyAuthenticationFilter(new ApiKeyProperties("fixture")))
            .build();
    mvc.perform(get("/api/v1/resume-overview")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
    mvc.perform(get("/api/v1/resume-overview").header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects[0].count").value(2));
    mvc.perform(get("/api/v1/projects/A/resume-overview").header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latest.savedAt").value("1970-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.latest.request").value("password=[REDACTED]"));
    mvc.perform(
            get("/api/v1/projects/foreign/resume-overview")
                .header("Authorization", "Bearer fixture"))
        .andExpect(status().isNotFound());
  }
}
