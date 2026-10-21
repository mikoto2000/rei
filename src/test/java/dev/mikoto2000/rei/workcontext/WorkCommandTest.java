package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 作業コマンドの結果と状態の契約を検証するテスト。 */
class WorkCommandTest {
  /** シェル表示履歴および更新について、共有サービスを利用することを確認する。 */
  @Test
  void shellShowHistoryAndUpdateUseSharedServiceAndCapturedSession() {
    var service = mock(WorkContextService.class);
    var projects = mock(ProjectService.class);
    var git = mock(WorkContextGit.class);
    var project = new ProjectContext(UUID.randomUUID().toString(), "other current", Path.of("."));
    when(projects.currentContext()).thenReturn(project);
    when(projects.currentSessionId()).thenReturn("session-owner");
    when(service.current(project.id())).thenReturn(Optional.empty());
    when(service.history(project.id(), 100)).thenReturn(List.of());
    when(service.update("session-owner", null, true)).thenReturn(Optional.empty());
    var output = new StringWriter();
    var command = new WorkCommand(service, projects, git, mock(CommandCancellationService.class));
    var cli = new CommandLine(command);
    command.setShellOutput(new PrintWriter(output));
    assertEquals(0, cli.execute());
    assertTrue(output.toString().contains("まだありません"));
    assertEquals(0, cli.execute("show"));
    assertEquals(0, cli.execute("history"));
    assertEquals(0, cli.execute("update"));
    verify(service).update("session-owner", null, true);
  }
}
