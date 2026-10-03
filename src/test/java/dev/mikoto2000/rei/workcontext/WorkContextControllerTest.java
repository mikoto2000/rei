package dev.mikoto2000.rei.workcontext;
import java.util.*;
import java.time.Instant;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.web.*;
import dev.mikoto2000.rei.core.project.ProjectContext;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class WorkContextControllerTest {
  @Test void readsCurrentAndHistoryAndUpdatesBySessionRatherThanUiSelection() throws Exception {
    var service=mock(WorkContextService.class);var properties=new WorkContextProperties(false,true,1200,12000,2,20);
    var project=new ProjectContext(UUID.randomUUID().toString(),"project",Path.of("."));
    when(service.project("a")).thenReturn(project);
    var now=Instant.parse("2026-10-03T00:00:00Z");var c=new WorkContext("a",1,now,now,null,List.of(),Set.of());
    when(service.current("a")).thenReturn(Optional.of(c));when(service.history("a",20)).thenReturn(List.of(c));
    when(service.update("session-a",null)).thenReturn(Optional.of(c));
    var mvc=MockMvcBuilders.standaloneSetup(new WorkContextController(service,mock(WorkContextGit.class),properties)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/v1/projects/a/work-context")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
    mvc.perform(get("/api/v1/projects/a/work-context/history")).andExpect(status().isOk()).andExpect(jsonPath("$[0].revision").value(1));
    mvc.perform(post("/api/v1/sessions/session-a/work-context/update")).andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value("a"));
    verify(service).update("session-a",null);
    mvc.perform(get("/api/v1/projects/a/work-context/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.autoPresent").value(true));
  }
}
