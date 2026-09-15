package dev.mikoto2000.rei.externalagent;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.llm.ConversationIds;

/** Thin asynchronous shell adapter. ChatExecutionService dispatches the same domain service. */
@Component
@Command(name = "agent", description = "Request an external agent review: /agent codex review [target]")
public class ExternalAgentCommand implements java.util.concurrent.Callable<Integer> {
  private final ConversationInputRouter router;
  @Parameters(arity = "0..*", paramLabel = "AGENT ACTION [TARGET]") private String[] arguments;
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public ExternalAgentCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public ExternalAgentCommand(ConversationInputRouter router) { this.router = router; }
  @Override public Integer call() {
    String text = "/agent" + (arguments == null ? "" : " " + String.join(" ", arguments));
    try { ExternalAgentCommandRequest.parse(text); }
    catch (IllegalArgumentException error) { spec.commandLine().getErr().println(error.getMessage()); return 2; }
    if (router == null) { spec.commandLine().getErr().println("External agent runtime unavailable"); return 2; }
    router.submitNewRun(ProjectService.currentProjectOrStartupDirectory(), ConversationIds.currentChat(), text);
    return 0;
  }
}
