package dev.mikoto2000.rei.shellautomation;

import dev.mikoto2000.rei.tooldelivery.ReadingRun;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** Uses request-scoped ownership, never a global selected project or a worker ThreadLocal. */
@Component
public final class ShellEnvironmentAdvisor implements BaseAdvisor {
  private final ShellEnvironmentService environment;

  public ShellEnvironmentAdvisor(ShellEnvironmentService environment) {
    this.environment = environment;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var user = request.prompt().getUserMessage();
    ReadingRun run =
        request.context().values().stream()
            .filter(ReadingRun.class::isInstance)
            .map(ReadingRun.class::cast)
            .findFirst()
            .orElse(null);
    if (user == null || run == null) return request;
    var detected = environment.current();
    String context =
        "# Shell execution environment\n"
            + "OS: "
            + literal(detected.os())
            + "\nShell: "
            + literal(detected.shell())
            + "\nExecutable: "
            + literal(detected.executable())
            + "\nVersion: "
            + literal(detected.version())
            + "\nDetection: "
            + detected.probeStatus()
            + "\nWorking directory: "
            + literal(run.projectRoot().toAbsolutePath().normalize().toString())
            + "\nOutput: "
            + detected.outputEncoding()
            + "\nAutomation uses a fresh non-interactive shell; PowerShell profiles are disabled."
            + "\nUse the detected shell's syntax; do not nest another shell to work around a failure."
            + "\nFor PowerShell native commands, check $LASTEXITCODE immediately after each step,"
            + " handle command-specific expected codes, and explicitly exit on unexpected failure."
            + " A later successful command can hide an earlier native failure."
            + "\nCommands may not be installed. Use getShellEnvironment to check required executables before retrying."
            + "\nUse readMultiFile/findFile/grep for file inspection; use runCommand for Git, builds and tests.\n\n";
    var replacement = user.mutate().text(context + user.getText()).build();
    var messages = new ArrayList<Message>(request.prompt().getInstructions());
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) == user) {
        messages.set(i, replacement);
        break;
      }
    }
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }

  private static String literal(String value) {
    return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return -99;
  }
}
