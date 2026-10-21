package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 環境診断コマンドの結果と状態の契約を検証するテスト。 */
class DoctorCommandTest {
  /** 実行環境の診断で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultAndDetailsOnlyPerformPassiveDiagnosis() {
    var service = mock(DoctorService.class);
    when(service.passive(anyBoolean())).thenReturn(List.of());
    var command = new CommandLine(new DoctorCommand(service));
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute());
    verify(service).passive(false);
    assertEquals(0, command.execute("--details"));
    verify(service).passive(true);
    verifyNoMoreInteractions(service);
  }

  /** 実行環境の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownActiveCheckIsRejectedWithoutDiagnosis() {
    var service = mock(DoctorService.class);
    var command = new CommandLine(new DoctorCommand(service));
    command.setErr(new PrintWriter(new StringWriter()));
    assertNotEquals(0, command.execute("--check", "inference"));
    verifyNoInteractions(service);
  }

  /** 実行環境の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rootRegistersDoctorAndCanDisableItWithoutRemovingOtherCommands() {
    var root = new dev.mikoto2000.rei.ui.shell.RootCommand();
    var command = new CommandLine(root);
    assertTrue(command.getSubcommands().containsKey("doctor"));
    org.springframework.test.util.ReflectionTestUtils.setField(root, "doctorEnabled", false);
    root.configureCommands(command);
    assertFalse(command.getSubcommands().containsKey("doctor"));
    assertTrue(command.getSubcommands().containsKey("agent"));
  }

  /** 実行環境の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unexpectedConfigurationFailureCannotLeakExceptionDetails() {
    var service = mock(DoctorService.class);
    when(service.passive(anyBoolean()))
        .thenThrow(new IllegalArgumentException("URL-SECRET token=KEY-SECRET"));
    var error = new StringWriter();
    var command = new CommandLine(new DoctorCommand(service));
    command.setErr(new PrintWriter(error));
    assertEquals(2, command.execute());
    assertTrue(error.toString().contains("受動診断に失敗しました (IllegalArgumentException)"));
    assertFalse(error.toString().contains("URL-SECRET"));
    assertFalse(error.toString().contains("KEY-SECRET"));
  }
}
