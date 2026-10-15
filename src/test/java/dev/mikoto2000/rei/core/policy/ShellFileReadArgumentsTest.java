package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ShellFileReadArgumentsTest {
  static String input(String command) {
    return JsonMapper.builder()
        .build()
        .writeValueAsString(java.util.Map.of("request", java.util.Map.of("command", command)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Get-Content -Raw 'docs/a.md'",
        "gc docs/a.md",
        "cat docs/a.md",
        "type docs\\a.md",
        "head -n 20 docs/a.md",
        "tail --lines=20 docs/a.md",
        "more docs/a.md",
        "less docs/a.md",
        "git status; Get-Content docs/a.md",
        "git status && cat docs/a.md",
        "git status\ncat docs/a.md",
        "Get-ChildItem docs | Get-Content",
        "$text = Get-Content docs/a.md",
        "$text=(Get-Content docs/a.md)",
        "$text=Get-Content docs/a.md",
        "$text =Get-Content docs/a.md",
        "& 'Get-Content' -Raw docs/a.md",
        "Microsoft.PowerShell.Management\\Get-Content docs/a.md",
        "powershell -NoProfile -Command \"Get-Content -Raw 'docs/a.md'\"",
        "pwsh -c 'Get-Content docs/a.md'",
        "powershell -Command Get-Content docs/a.md",
        "cmd.exe /c type docs\\a.md",
        "bash -lc 'cat docs/a.md'",
        "sh -c \"head -n 20 docs/a.md\"",
        "& 'C:\\Program Files\\PowerShell\\pwsh.exe' -Command 'Get-Content docs/a.md'",
        "echo $(cat docs/a.md)",
        "cat < docs/a.md",
        "cat -- 'docs/a file.md'"
      })
  void rejectsReadersAndGuidesToExactPageContinuation(String command) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () -> ShellFileReadArguments.validate("runCommand", input(command)));
    assertTrue(error.getMessage().contains("readMultiFile"));
    assertTrue(error.getMessage().contains("nextRead"));
    assertTrue(error.getMessage().contains("expectedVersion"));
    assertFalse(
        error.getMessage().contains(command)); // Avoid echoing arbitrary shell text or credentials.
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "git status --short",
        "git show HEAD:README.md",
        "git diff | head -n 20",
        "git log | head -20",
        "mvn test",
        "npm run build",
        "docker compose logs --tail 20",
        "git log | tail -n 5",
        "Write-Output 'Get-Content docs/a.md'",
        "echo \"cat docs/a.md\"",
        "git commit -m \"fix: cat and Get-Content\"",
        "npm test -- --grep 'cat docs/a.md'",
        "powershell -Command \"Write-Output 'Get-Content docs/a.md'\"",
        "bash -c \"echo 'cat docs/a.md'\"",
        "# cat docs/a.md\ngit status",
        "git diff | cat",
        "$text='Get-Content'",
        "Write-Output ('Get-Content')",
        "git diff | cat 2>/dev/null",
        "mvn test | head -n 20 2>$null"
      })
  void preservesDevelopmentCommandsAndLiteralArguments(String command) {
    assertDoesNotThrow(() -> ShellFileReadArguments.validate("runCommand", input(command)));
  }

  @Test
  void otherToolsAndMalformedRunCommandArgumentsAreHandledSeparately() {
    assertDoesNotThrow(() -> ShellFileReadArguments.validate("readMultiFile", "not json"));
    for (String value :
        new String[] {
          "{}",
          "{\"command\":\"cat a\"}",
          "{\"request\":{\"command\":false}}",
          "{\"request\":{\"command\":\"git status\",\"command\":\"cat a\"}}",
          "{} {}"
        }) {
      assertThrows(
          InvalidToolArgumentsException.class,
          () -> ShellFileReadArguments.validate("runCommand", value));
    }
  }
}
