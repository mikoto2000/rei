package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name="session", description="会話 Session の作成・選択", mixinStandardHelpOptions=true,
    subcommands={NewConversationCommand.class, ResumeConversationCommand.class})
public final class SessionCommand implements Runnable {
  @Spec private CommandSpec spec;

  @Override public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }
}
