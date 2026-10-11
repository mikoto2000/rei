package dev.mikoto2000.rei.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.*;
import dev.mikoto2000.rei.core.completion.*;

/** Client-local selection, JLine input and ordinary terminal output. */
public final class TerminalClient implements AutoCloseable {
  private final BackendClient backend;
  private final Terminal terminal;
  private final LineReader reader;
  private final ClientHistory history;
  private final ExecutorService streams=Executors.newVirtualThreadPerTaskExecutor();
  private Future<?> watching;
  private volatile boolean attached;
  private final AtomicLong subscriptionGeneration=new AtomicLong();
  private String mode="EXCLUSIVE";
  private String project,projectName="none",session,run;
  private volatile List<String> projectIds=List.of(),sessionIds=List.of();
  private boolean recordHistory;
  private static final List<String> COMMANDS=java.util.stream.Stream.concat(java.util.stream.Stream.of("/help","/exit","/project","/session","/chat","/run-mode","/cancel","/runs","/watch","/receipt","/input-history","/paste"),RemoteCommands.NAMES.stream()).sorted().toList();
  public TerminalClient(BackendClient backend,Path historyDirectory,boolean noHistory)throws IOException {
    this(backend,historyDirectory,noHistory,TerminalBuilder.builder().system(true).build());
  }
  TerminalClient(BackendClient backend,Path historyDirectory,boolean noHistory,Terminal terminal)throws IOException {
    this.backend=backend;history=new ClientHistory(historyDirectory);recordHistory=!noHistory;history.enabled(recordHistory);
    this.terminal=terminal;
    var memory=new DefaultHistory(){@Override public void add(java.time.Instant time,String line){if(recordHistory&&history.accepts(line))super.add(time,line);}};
    reader=LineReaderBuilder.builder().terminal(terminal).parser(commandParser()).history(memory).completer(this::complete).build();
    reader.setVariable(LineReader.HISTORY_SIZE,1000);reader.setVariable(LineReader.HISTORY_FILE_SIZE,1000);
    if(recordHistory)for(String line:history.entries())memory.add(java.time.Instant.now(),line);
    terminal.handle(Terminal.Signal.INT,signal->{backend.detach();attached=false;});
  }
  public void selectInitialProject(Path path)throws IOException,InterruptedException {
    if(path==null)return;
    var value=backend.post("/api/v1/projects/register",Map.of("path",path.toString()));selectProject(value.path("id").asText());
  }
  private final CompletionEngine paths=new CompletionEngine().register(new FilePathCompletionProvider());
  private void complete(LineReader input,ParsedLine line,List<Candidate> candidates) {
    List<String> values=line.wordIndex()==0?COMMANDS:line.words().getFirst().equals("/project")?projectIds:line.words().getFirst().equals("/session")?sessionIds:List.of();
    for(String value:values)if(value.startsWith(line.word()))candidates.add(new Candidate(value));
    if(line.words().size()>1&&line.words().getFirst().equals("/project")&&line.words().get(1).equals("register")) {
      var context=new CompletionContext(line.line(),line.cursor(),line.words(),line.wordIndex(),line.wordIndex()-1,line.word(),List.of("project","register"),Set.of("directory"),List.of(),Path.of("").toAbsolutePath());
      for(var value:paths.complete(context))candidates.add(new Candidate(value.value(),value.display(),"CLI local paths",value.description(),null,null,value.appendSpace()));
    }
  }
  public void run()throws IOException {
    try{refresh();}catch(Exception error){out("Completion metadata unavailable; /project list can retry.");}
    out("Connected. /help lists commands. Ctrl+C detaches output; /cancel RUN_ID cancels a Run.");
    while(true) {
      String line;
      try {line=reader.readLine("["+safeOutput(projectName)+" / "+safeOutput(session==null?"new":session)+"]> ");}
      catch(UserInterruptException interrupt){backend.detach();attached=false;continue;}
      catch(EndOfFileException eof){return;}
      if(line.isBlank())continue;
      if(line.equals("/exit")||line.equals("/quit"))return;
      try {
        if(line.equals("/paste")) {
          var paste=new StringBuilder();for(;;){String part=reader.readLine("paste> ");if(part.equals("."))break;if(paste.length()+part.length()>16384)throw new IllegalArgumentException("Input capacity exceeded");paste.append(part).append('\n');}
          send(paste.toString());continue;
        }
        var message=new StringBuilder(line);
        while(message.toString().endsWith("\\")) {message.setLength(message.length()-1);message.append('\n').append(reader.readLine("...> "));if(message.length()>16384)throw new IllegalArgumentException("Input capacity exceeded");}
        history.record(message.toString());
        if(message.toString().stripLeading().startsWith("/"))command(message.toString().strip());else send(message.toString());
      } catch(BackendClient.UncertainAcceptanceException uncertain){out(uncertain.getMessage());}
      catch(BackendClient.ApiException rejected){out(rejected.getMessage());}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
      catch(UserInterruptException interrupted){/* The input was not sent. */}
      catch(EndOfFileException eof){return;}
      catch(IllegalArgumentException invalid){out(invalid.getMessage());}
      catch(IOException failure){out("Backend connection failed. Inspect the Run or receipt before submitting again.");}
    }
  }
  private void refresh()throws IOException,InterruptedException {
    var projects=backend.get("/api/v1/projects");var ids=new ArrayList<String>();for(var value:projects)ids.add(value.path("id").asText());projectIds=List.copyOf(ids);
    if(project!=null){var sessions=backend.get("/api/v1/sessions?projectId="+BackendClient.segment(project));ids=new ArrayList<>();for(var value:sessions.path("items"))ids.add(value.path("sessionId").asText());sessionIds=List.copyOf(ids);}
  }
  private void selectProject(String id)throws IOException,InterruptedException {
    JsonNode match=null;for(var value:backend.get("/api/v1/projects"))if(id.equals(value.path("id").asText()))match=value;
    if(match==null)throw new IllegalArgumentException("Unknown registered Project ID");
    project=id;projectName=match.path("name").asText();session=null;refresh();
  }
  private void send(String message)throws IOException,InterruptedException {
    send(message,mode);
  }
  private void send(String message,String access)throws IOException,InterruptedException {
    requireProject();if(message.length()>16384)throw new IllegalArgumentException("Input capacity exceeded");
    String key=UUID.randomUUID().toString();out("Receipt: "+key);
    var accepted=backend.submit(project,session,message,key,access);session=accepted.path("sessionId").asText();run=accepted.path("runId").asText();out("Run: "+run);watch(run);refresh();
  }
  String projectId(){return project;}
  private static DefaultParser commandParser(){return new DefaultParser().escapeChars(null);}
  static List<String> commandWords(String line){return commandParser().parse(line,line.length(),Parser.ParseContext.ACCEPT_LINE).words();}
  void command(String line)throws IOException,InterruptedException {
    var words=commandWords(line);
    String name=words.getFirst();String action=words.size()>1?words.get(1):"list";
    if(name.equals("/mode")&&session==null) {
      requireProject();
      if(action.equals("list")||action.equals("status")){out("Response mode: auto (no active Session)");return;}
      // Validate before creating an empty Session. Run access is unchanged.
      RemoteCommands.route(words,project,"validation");createSession(null);
    }
    if(RemoteCommands.NAMES.contains(name)&&!(name.equals("/history")&&Set.of("off","on").contains(action))) {
      var request=RemoteCommands.route(words,project,session);
      show(request.method().equals("GET")?backend.get(request.path()):backend.mutation(request.method(),request.path(),request.body()));return;
    }
    switch(name) {
      case "/help"->out(String.join(" ",COMMANDS)+"\n/project list|select ID|register LOCAL_PATH; /session list|select ID|new|end; /watch RUN_ID; /history off|on. Unsupported commands are never sent to the model.");
      case "/project"->{switch(action){case "list"->{show(backend.get("/api/v1/projects"));refresh();}case "select"->selectProject(argument(words,2));case "register"->{var value=backend.post("/api/v1/projects/register",Map.of("path",Path.of(argument(words,2)).toAbsolutePath().normalize().toString()));selectProject(value.path("id").asText());}default->throw new IllegalArgumentException("Usage: /project list|select ID|register LOCAL_PATH");}}
      case "/session"->{requireProject();switch(words.size()==1?"show":action){case "list"->show(backend.get("/api/v1/sessions?projectId="+BackendClient.segment(project)));case "show"->{if(session==null)out("No Session selected");else show(backend.get("/api/v1/sessions/"+BackendClient.segment(session)));}case "select","switch","resume"->{String id=argument(words,2);var value=backend.get("/api/v1/sessions/"+BackendClient.segment(id));if(!project.equals(value.path("projectId").asText()))throw new IllegalArgumentException("Session belongs to another Project");session=id;}case "new"->{if(words.size()>3)throw new IllegalArgumentException("Usage: /session new [TITLE]");createSession(words.size()==3?words.get(2):null);}case "end"->{requireSession();show(backend.post("/api/v1/sessions/"+BackendClient.segment(session)+"/end",Map.of("projectId",project)));session=null;}default->throw new IllegalArgumentException("Usage: /session list|show|switch ID|new [TITLE]|end");}refresh();}
      case "/chat"->{var input=ChatInput.parse(words,mode);if(input.runId()==null)send(input.message(),input.mode());else{requireProject();requireSession();show(backend.post("/api/v1/runs/"+BackendClient.segment(input.runId())+"/input",Map.of("projectId",project,"sessionId",session,"message",input.message())));}}
      case "/run-mode"->{String selected=argument(words,1).toUpperCase(Locale.ROOT).replace('-','_');if(!Set.of("EXCLUSIVE","READ_ONLY","CONVERSATION").contains(selected))throw new IllegalArgumentException("Usage: /run-mode exclusive|read-only|conversation");mode=selected;out("Run access: "+mode);}
      case "/cancel"->show(backend.post("/api/v1/runs/"+BackendClient.segment(argument(words,1))+"/cancel",Map.of()));
      case "/watch"->{run=argument(words,1);watch(run);}
      case "/runs"->{if(words.size()>1)show(backend.get("/api/v1/runs/"+BackendClient.segment(words.get(1))));else out(run==null?"No Run selected":run);}
      case "/receipt"->{String key=argument(words,1);if(!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid receipt key");show(backend.get("/api/v1/chat/receipts/"+key));}
      case "/history","/input-history"->{if(!Set.of("off","on").contains(action))throw new IllegalArgumentException("Usage: /input-history off|on");recordHistory=action.equals("on");history.enabled(recordHistory);out("Input history "+action);}
      default->throw new IllegalArgumentException("Unsupported CLI command: "+name+". Use legacy-shell; this input was not sent to the model.");
    }
  }
  private void createSession(String title)throws IOException,InterruptedException {
    var body=new LinkedHashMap<String,Object>();body.put("projectId",project);body.put("title",title);
    var value=backend.post("/api/v1/sessions",body);session=value.path("sessionId").asText();show(value);
  }
  private void watch(String id) {
    long generation=subscriptionGeneration.incrementAndGet();backend.detach();if(watching!=null)watching.cancel(true);attached=true;
    watching=streams.submit(()->{
      var sequence=new AtomicLong();var partialMessages=new HashSet<String>();int reconnects=0;
      while(attached&&generation==subscriptionGeneration.get()&&!Thread.currentThread().isInterrupted()) {
        try {
          backend.events(id,sequence.get(),event->{
            if(!attached||generation!=subscriptionGeneration.get())return;
            try {
              var value=BackendClient.JSON.readTree(event.data());if(!id.equals(value.path("runId").asText()))throw new IllegalArgumentException("Event Run identity mismatch");
              sequence.set(event.sequence());
              String messageId=value.path("payload").path("messageId").asText();
              if(event.type().equals("message.delta")){partialMessages.add(messageId);out(value.path("payload").path("delta").asText());}
              else if(event.type().equals("message.completed")){if(!partialMessages.remove(messageId))out(value.path("payload").path("text").asText());}
              else if(event.type().startsWith("tool.")||event.type().startsWith("agent.run."))out(event.type()+": "+value.path("payload"));
              if(Set.of("agent.run.completed","agent.run.failed","agent.run.cancelled").contains(event.type()))attached=false;
            }catch(IOException invalid){throw new IllegalArgumentException("Malformed Run event");}
          });
          if(!attached)return;
        } catch(BackendClient.ApiException error) {
          if(error.status()==409){out("Event history has a gap; output is incomplete. Fetching Run status and saved Session turns.");poll(id,generation);return;}
          if(error.status()==401||error.status()==403){out("Backend authentication failed");return;}
        } catch(InterruptedException interrupt){Thread.currentThread().interrupt();return;}
        catch(IOException|IllegalArgumentException error){if(!attached)return;}
        if(++reconnects>3){out("Stream disconnected. /watch "+id+" reconnects; the Run continues on Backend.");return;}
        try{Thread.sleep(250L*reconnects);}catch(InterruptedException interrupt){Thread.currentThread().interrupt();return;}
      }
    });
  }
  private void poll(String id,long generation) {
    for(int attempt=0;attempt<60&&attached&&generation==subscriptionGeneration.get();attempt++) {
      try {
        var status=backend.get("/api/v1/runs/"+BackendClient.segment(id));String state=status.path("status").asText();
        if(Set.of("COMPLETED","FAILED","CANCELLED","UNKNOWN").contains(state)) {
          out("Run status: "+state+" (stream output incomplete)");String savedSession=status.path("sessionId").asText();
          if(!savedSession.isEmpty())show(backend.get("/api/v1/sessions/"+BackendClient.segment(savedSession)+"/turns"));return;
        }
        Thread.sleep(1000);
      }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
      catch(IOException connection){out("Status polling failed; inspect /runs "+id);return;}
    }
    out("Status polling ended; inspect /runs "+id+". Backend execution was not cancelled.");
  }
  private void requireProject(){if(project==null)throw new IllegalArgumentException("Select a Project with /project select ID or /project register LOCAL_PATH");}
  private void requireSession(){if(session==null)throw new IllegalArgumentException("No Session selected");}
  private static String argument(List<String> words,int index){if(index>=words.size())throw new IllegalArgumentException("Missing command argument");return words.get(index);}
  private void show(JsonNode value)throws IOException{out(BackendClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
  private String safeOutput(String value){return backend.redact(value).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]","");}
  private void out(String value){reader.printAbove(safeOutput(value));}
  @Override public void close()throws IOException {attached=false;backend.detach();streams.shutdownNow();history.close();terminal.close();}
}
