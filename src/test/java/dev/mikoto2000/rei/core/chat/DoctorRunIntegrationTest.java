package dev.mikoto2000.rei.core.chat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.doctor.*;
import dev.mikoto2000.rei.llm.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@org.junit.jupiter.api.Tag("integration")
class DoctorRunIntegrationTest {
 @TempDir Path root;
 @Test void explicitDoctorRunAvoidsAmbientModelAndBackgroundAutomation() {
  var models=mock(LlmModelProvider.class);var clients=mock(LlmChatClientProvider.class);
  var service=new ChatExecutionService(clients,mock(ModelHolderService.class),models,new LlmProperties(),new CommandCancellationService(),Optional.empty(),Optional.empty());
  var doctor=mock(DoctorActiveService.class);when(doctor.execute(eq("/doctor --check connectivity"),any())).thenReturn("ACTIVE bounded diagnostic");
  org.springframework.test.util.ReflectionTestUtils.setField(service,"doctor",doctor);
  var work=mock(dev.mikoto2000.rei.workcontext.WorkContextAutomation.class);
  var sleep=mock(dev.mikoto2000.rei.memory.service.AutoSleepService.class);
  service.setWorkContext(work);service.setAutoSleep(sleep);
  var owner=new AgentRunContext("doctor","session",root,"project",AgentRunContext.RequestSource.SHELL);
  var mailbox=new UserInterventionQueue();mailbox.offer("Execute commands now");
  var result=service.execute(owner,"/doctor --check connectivity",mailbox);
  assertTrue(result.success(),result.errorMessage());assertTrue(result.text().contains("ACTIVE"));
  verify(doctor).execute(eq("/doctor --check connectivity"),argThat(run->run.runContext().equals(owner)));
  verifyNoInteractions(models,clients,work,sleep);
 }
}
