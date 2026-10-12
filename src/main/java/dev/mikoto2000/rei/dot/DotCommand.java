package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.util.*;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name = "dot", description = "てんてんへの永続依頼を作成・確認・取消", mixinStandardHelpOptions = true)
public final class DotCommand implements java.util.concurrent.Callable<Integer> {
  private final DotDelegationService service;
  private final ProjectService projects;

  @Parameters(arity = "0..*", paramLabel = "ACTION [ID|INSTRUCTION] [OPTIONS]")
  String[] arguments;

  @Option(names = "--project-id")
  String projectId;

  @Option(names = "--request-id")
  String creationId;

  @Option(names = "--category")
  String category;

  @Option(names = "--context")
  String context;

  @Option(names = "--expected-output")
  String expected;

  @Option(names = "--constraint")
  List<String> constraints;

  @Option(names = "--acceptance-criterion")
  List<String> criteria;

  @Option(names = "--timeout")
  Integer timeout;

  @Option(names = "--status")
  String reviewStatus;

  @Option(names = "--note")
  String note;

  @Option(names = "--satisfied")
  String satisfied;

  @Option(names = "--yes")
  boolean confirmed;

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  private List<String> words() {
    var out = new ArrayList<String>(arguments == null ? List.of() : Arrays.asList(arguments));
    String[][] options = {
      {"--project-id", projectId},
      {"--request-id", creationId},
      {"--category", category},
      {"--context", context},
      {"--expected-output", expected},
      {"--timeout", timeout == null ? null : timeout.toString()},
      {"--status", reviewStatus},
      {"--note", note},
      {"--satisfied", satisfied}
    };
    for (var option : options)
      if (option[1] != null) {
        out.add(option[0]);
        out.add(option[1]);
      }
    if (constraints != null)
      for (String value : constraints) {
        out.add("--constraint");
        out.add(value);
      }
    if (criteria != null)
      for (String value : criteria) {
        out.add("--acceptance-criterion");
        out.add(value);
      }
    if (confirmed) out.add("--yes");
    return out;
  }

  private DotResumeService resumes;

  @org.springframework.beans.factory.annotation.Autowired
  public void setResumes(DotResumeService resumes) {
    this.resumes = resumes;
  }

  private PrintWriter output;

  public DotCommand() {
    this(null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DotCommand(DotDelegationService service, ProjectService projects) {
    this.service = service;
    this.projects = projects;
  }

  public void setShellOutput(PrintWriter output) {
    this.output = output;
  }

  public Integer call() {
    var out = output == null ? spec.commandLine().getOut() : output;
    try {
      var request = DotCommandRequest.parse(words());
      String project =
          request.projectId() == null ? projects.currentContext().id() : request.projectId();
      Object result =
          switch (request.action()) {
            case "request" ->
                service.createHuman(project, projects.currentSessionId(), request.payload());
            case "requests" -> service.list(project);
            case "status" ->
                resumes == null
                    ? service.get(project, request.requestId())
                    : resumes.status(project, request.requestId());
            case "result" -> service.result(project, request.requestId());
            case "cancel" -> service.cancel(project, request.requestId());
            case "verify" -> resumes.verify(project, request.requestId(), request.payload());
            case "resume" -> resumes.resume(project, request.requestId(), request.payload());
            default -> throw new DotException("INVALID_ARGUMENT");
          };
      out.println(dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(result));
      return 0;
    } catch (Exception e) {
      out.println("[error] " + (e instanceof DotException dot ? dot.code : "INVALID_ARGUMENT"));
      return 2;
    } finally {
      out.flush();
    }
  }
}
