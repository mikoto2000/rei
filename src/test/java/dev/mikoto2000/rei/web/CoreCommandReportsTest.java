package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.checkpoint.*;
class CoreCommandReportsTest {
 @Test void checkpointLabelsMatchResumeListAndWorkEmptyHistoryHasShellMessage() {
  var service=mock(PersistentCheckpointService.class);var saved=PersistentCheckpoint.initial("task","project","session","run",Path.of("."),"request");when(service.list("project")).thenReturn(List.of(saved));var checkpoints=new CheckpointController(service);assertEquals("[task [RUNNING] request]",checkpoints.reportList("project").text());
  var work=mock(WorkContextService.class);when(work.history("project",100)).thenReturn(List.of());var api=new WorkContextController(work,mock(WorkContextGit.class),new WorkContextProperties(false,true,1200,12000,120,20));assertEquals("Work Context: 履歴はまだありません。",api.reportHistory("project").text());
 }
}
