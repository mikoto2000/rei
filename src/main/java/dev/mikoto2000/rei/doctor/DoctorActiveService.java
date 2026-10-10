package dev.mikoto2000.rei.doctor;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.dependency.JavaHttpDependencyProbe;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.externalagent.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.voice.VoiceProperties;
import static dev.mikoto2000.rei.doctor.DoctorResult.Status.*;

/** Explicit diagnostics in the captured Run, never autonomous repair or authentication. */
@Service
public class DoctorActiveService {
  public record Report(List<DoctorResult> results) {
    public Report {results=List.copyOf(results);}
    public String render() {
      var text=new StringBuilder("ACTIVE diagnosis: only explicitly requested and permitted observations. Configuration, response, inference, CLI version and device acquisition are separate facts.\n");
      for(var r:results)text.append(r.id()).append(" ").append(r.status()).append(" @ ").append(r.observedAt())
          .append("\n  Method: ").append(r.method()).append("\n  Evidence: ").append(r.evidence())
          .append("\n  Possible causes: ").append(r.causeCandidates()).append("\n  Next: ").append(r.nextAction()).append('\n');
      return text.toString();
    }
  }
  private final DoctorService passive;
  private final Environment environment;
  private final LlmModelProvider models;
  private final ModelHolderService selectedModel;
  private final JavaHttpDependencyProbe http;
  private final CodexProperties codex;
  private final ClaudeCodeProperties claude;
  private final VoiceProperties voice;
  private final DoctorMicrophoneProbe microphone;
  private final ExternalAgentProcessRunner processes;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorActiveService(DoctorService passive,Environment environment,LlmModelProvider models,ModelHolderService selectedModel,
      JavaHttpDependencyProbe http,CodexProperties codex,ClaudeCodeProperties claude,VoiceProperties voice,DoctorMicrophoneProbe microphone) {
    this(passive,environment,models,selectedModel,http,codex,claude,voice,microphone,new ExternalAgentProcessRunner(),Clock.systemUTC());
  }
  public DoctorActiveService(DoctorService passive,Environment environment,LlmModelProvider models,ModelHolderService selectedModel,
      JavaHttpDependencyProbe http,CodexProperties codex,ClaudeCodeProperties claude,VoiceProperties voice,DoctorMicrophoneProbe microphone,
      ExternalAgentProcessRunner processes,Clock clock) {
    this.passive=passive;this.environment=environment;this.models=models;this.selectedModel=selectedModel;this.http=http;
    this.codex=codex;this.claude=claude;this.voice=voice;this.microphone=microphone;this.processes=processes;this.clock=clock;
  }
  private record Planned(DoctorRequest.Check check,String tool,String input,String call,String target,boolean enabled,OpenAiChatOptions inferenceOptions) {}
  public Report active(RunExecutionContext run,DoctorRequest request) {
    run.checkActive();
    if(!environment.getProperty("rei.doctor.enabled",Boolean.class,true))return new Report(List.of(result("doctor",SKIPPED,"configuration","Doctor disabled","Administrator configuration","Enable doctor when needed")));
    var owner=Objects.requireNonNull(run.runContext(),"Captured Run required");
    var plans=new ArrayList<Planned>();
    for(var check:request.checks()) {
      String tool=switch(check) {case CONNECTIVITY->"doctorConnectivity";case INFERENCE->"doctorInference";case CODEX->"doctorCodexVersion";case CLAUDE->"doctorClaudeVersion";case MICROPHONE->"doctorMicrophone";};
      String target=switch(check) {case CONNECTIVITY->passive.endpoint();case INFERENCE->selectedModel.get();case CODEX->codex.getCommand();case CLAUDE->claude.getCommand();case MICROPHONE->voice.getDeviceId();};
      boolean enabled=switch(check) {case CODEX->codex.isEnabled();case CLAUDE->claude.isEnabled();default->true;};
      OpenAiChatOptions inferenceOptions=null;
      String connectionIdentity="";
      if(check==DoctorRequest.Check.INFERENCE) {
        var defaults=models.chatOptions(LlmFeature.CHAT,target);
        inferenceOptions=defaults==null?OpenAiChatOptions.builder().model(target).build():defaults.mutate().build();
        connectionIdentity=Objects.toString(passive.endpoint(),"")+"|"+Objects.toString(inferenceOptions.getBaseUrl(),"")+"|"
            +Objects.toString(inferenceOptions.getApiKey(),"")+"|"+Objects.toString(inferenceOptions.getModel(),"");
      }
      String fingerprint=ImplementationProposal.sha256((check+"|"+enabled+"|"+Objects.toString(target,"")+"|"+connectionIdentity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String input="{\"check\":\""+check+"\",\"configurationFingerprint\":\""+fingerprint+"\"}";
      plans.add(new Planned(check,tool,input,UUID.randomUUID().toString(),target,enabled,inferenceOptions));
    }
    run.planTools(plans.stream().map(p->new AssistantMessage.ToolCall(p.call(),"function",p.tool(),p.input())).toList());
    var results=new ArrayList<DoctorResult>();
    for(var plan:plans) {
      run.checkActive();String call=run.claimToolId(plan.tool(),plan.input());
      boolean begun=false;
      try {
        run.checkToolPermission(plan.tool(),plan.input());
        run.beginDurableTool(call,plan.tool(),plan.input());begun=true;
        Duration timeout=timeout();
        DoctorResult observed=switch(plan.check()) {
          case CONNECTIVITY->connectivity(run,plan.target(),timeout);
          case INFERENCE->inference(run,plan.inferenceOptions(),timeout);
          case CODEX,CLAUDE->version(run,plan,timeout);
          case MICROPHONE->microphone(run,plan.target(),timeout);
        };
        run.checkActive();results.add(observed);
        run.completeDurableTool(call,plan.tool(),observed.id()+" "+observed.status());
      }catch(ToolPermissionException denied) {
        results.add(result(plan.check().name().toLowerCase(Locale.ROOT),SKIPPED,"permission policy","Not executed: "+denied.decision(),"Existing Run permission policy","Use the existing approval flow and explicitly retry"));
      }catch(BoundedToolLoop.SharedBudgetExceeded exhausted) {
        results.add(result("llm.inference",SKIPPED,"Run budget","Inference not completed: shared model budget exhausted","Run budget","Retry in a Run with an available budget"));
        if(begun)run.failDurableTool(call,plan.tool(),exhausted);
      }catch(RuntimeException failure) {
        RunCancellation.propagate(failure);
        if(failure instanceof ExecutionStoppedException)throw failure;
        results.add(result(plan.check().name().toLowerCase(Locale.ROOT),UNVERIFIED,"bounded diagnostic","Diagnostic unavailable; exception details omitted","Configuration, provider or runtime limitation; cause not established","Inspect local configuration and explicitly retry"));
        if(begun)run.failDurableTool(call,plan.tool(),failure);
      }
    }
    return new Report(results);
  }
  private Duration timeout() {
    Duration value=org.springframework.boot.convert.DurationStyle.detectAndParse(environment.getProperty("rei.doctor.active-timeout","5s"));
    if(value.compareTo(Duration.ofMillis(100))<0||value.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Diagnostic timeout out of range");
    return value;
  }
  private DoctorResult connectivity(RunExecutionContext run,String endpoint,Duration timeout) {
    if(endpoint==null||endpoint.isBlank())return result("llm.connectivity",NOT_CONFIGURED,"configuration","Endpoint missing","Configuration","Configure the existing endpoint");
    URI uri=URI.create(endpoint);
    if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getHost()==null
        ||uri.getScheme()==null||!Set.of("http","https").contains(uri.getScheme().toLowerCase(Locale.ROOT)))
      return result("llm.connectivity",ERROR,"configuration validation","Unsafe diagnostic URL; no request sent","Unsupported URL or embedded credentials","Use an HTTP(S) endpoint without userinfo, query or fragment");
    var observed=http.connectivity(OpenAiCompatibleEndpoint.baseUrl(endpoint)+"/models",timeout,run::checkActive);
    return switch(observed.reason()) {
      case RESPONSE->result("llm.connectivity",observed.httpStatus()>=200&&observed.httpStatus()<300?OK:WARNING,"unauthenticated GET /v1/models; no redirects/body",
          "HTTP response status "+observed.httpStatus(),"Connectivity observed; authentication, inference and GPU state unverified","Use a separate explicit inference check if needed");
      case TIMEOUT->result("llm.connectivity",ERROR,"bounded HTTP request","TIMEOUT; no response observed","Server, network or timeout setting; not established","Check local endpoint/network and retry");
      case CONNECTION_FAILED->result("llm.connectivity",ERROR,"bounded HTTP request","CONNECTION_FAILED; no response observed","Endpoint listener or network unavailable; not established","Check server and connection settings locally");
      case UNAVAILABLE->result("llm.connectivity",UNVERIFIED,"bounded HTTP request","HTTP observation unavailable","Transport failure; details omitted","Check connection settings locally");
    };
  }
  private DoctorResult inference(RunExecutionContext run,OpenAiChatOptions captured,Duration timeout) {
    var model=models.subAgentChatModel();ToolLoopSupport.requireNoDefaultTools(model);
    var options=captured.mutate();
    var bounded=options.maxCompletionTokens(null).maxTokens(16).toolCallbacks(List.of()).toolChoice("none")
        .toolContext(Map.of(RunExecutionContext.KEY,run,AgentRunContext.class.getName(),run.runContext())).build();
    var inference=new BoundedToolLoop().runWithHistory(model,new Prompt(List.of(new SystemMessage("Return one short word. Do not call tools."),new UserMessage("Reply OK.")),bounded),
        new AtomicInteger(1),run.runContext(),run::checkActive,run.sharedLlmReservation());
    var cancelled=reactor.core.publisher.Flux.interval(Duration.ofMillis(50)).doOnNext(tick->run.checkActive()).then();
    BoundedToolLoop.Outcome outcome;
    try {
      outcome=reactor.core.publisher.Mono.firstWithSignal(inference,cancelled.then(reactor.core.publisher.Mono.<BoundedToolLoop.Outcome>never()))
          .timeout(timeout).block(timeout.plusSeconds(1));
    } catch(RuntimeException failure) {
      RunCancellation.propagate(failure);
      for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.util.concurrent.TimeoutException)
        return result("llm.inference",ERROR,"bounded fixed inference","TIMEOUT; model response not completed","Model pipeline or network timeout; internal state unverified","Check existing model settings and explicitly retry");
      throw failure;
    }
    boolean answered=outcome!=null&&!outcome.output().isBlank();
    return result("llm.inference",answered?OK:UNVERIFIED,"one fixed tool-free inference; existing Run/model budget",
        answered?"Non-empty model response observed; response content omitted":"No non-empty model response observed",
        "Configured model pipeline including existing fallback; primary server identity, GPU and model loading internals unverified","Inspect existing model configuration if needed");
  }
  private DoctorResult version(RunExecutionContext run,Planned plan,Duration timeout) {
    String id="external."+plan.check().name().toLowerCase(Locale.ROOT);
    if(!plan.enabled())return result(id,NOT_APPLICABLE,"configuration","Adapter disabled","Administrator configuration","Enable adapter only if needed");
    if(plan.target()==null||plan.target().isBlank())return result(id,NOT_CONFIGURED,"configuration","CLI command missing","Administrator configuration","Configure the existing CLI command");
    var output=processes.run(List.of(plan.target(),"--version"),run.runContext().projectRoot(),"",timeout,timeout,4096,()->run.isCancelled()||Thread.currentThread().isInterrupted());
    if(output.status()==ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException();
    boolean success=output.status()==ExternalAgentResult.Status.SUCCESS&&Integer.valueOf(0).equals(output.exitCode());
    return result(id,success?OK:ERROR,"configured CLI --version; bounded owned process",
        "Process status "+output.status()+"; exit code "+output.exitCode()+"; output omitted",
        "Executable observation only; authentication and native agent inference unverified","Check local CLI installation/configuration; authentication is a separate operation");
  }
  private DoctorResult microphone(RunExecutionContext run,String deviceId,Duration timeout) {
    var observed=microphone.check(deviceId,timeout,run::checkActive);
    var status=switch(observed) {case AVAILABLE->OK;case NOT_CONFIGURED->NOT_CONFIGURED;case UNAVAILABLE->ERROR;case TIMEOUT,BUSY->UNVERIFIED;};
    return result("voice.microphone",status,"explicit configured device acquisition and close; no recording start/read",
        "Acquisition status "+observed,"Acquisition only; audio signal quality unverified. A blocked native driver remains limited to one outstanding acquisition until it returns",
        "Select a device explicitly; on timeout cleanup is requested and further acquisition waits for the driver to return");
  }
  private DoctorResult result(String id,DoctorResult.Status status,String method,String evidence,String causes,String next) {
    return new DoctorResult(id,status,clock.instant(),method,evidence,causes,next);
  }
  public String execute(String text,RunExecutionContext run) {
    var request=DoctorRequest.parseText(text);
    if(request.checks().isEmpty())return DoctorService.render(passive.passive(request.details()));
    return request.plan()+"\n"+active(run,request).render();
  }
}
