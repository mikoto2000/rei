package dev.mikoto2000.rei.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.*;
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
  private Path projectDirectory;
  private volatile List<String> projectIds=List.of(),sessionIds=List.of();
  private volatile List<String> modelIds=List.of();
  private volatile List<String> projectPaths=List.of();
  private volatile List<String> allSessionIds=List.of();
  private volatile CachedCatalog catalog=new CachedCatalog(new dev.mikoto2000.rei.application.cli.CliCatalog(List.of()));
  private boolean catalogLoaded;
  private boolean recordHistory;
  private static final List<String> COMMANDS=java.util.stream.Stream.concat(java.util.stream.Stream.of("/help","/exit","/project","/session","/chat","/run-mode","/cancel","/runs","/watch","/receipt","/input-history","/paste","/sh"),RemoteCommands.NAMES.stream()).sorted().toList();
  public TerminalClient(BackendClient backend,Path historyDirectory,boolean noHistory)throws IOException {
    this(backend,historyDirectory,noHistory,TerminalBuilder.builder().system(true).build());
  }
  TerminalClient(BackendClient backend,Path historyDirectory,boolean noHistory,Terminal terminal)throws IOException {
    this.backend=backend;history=new ClientHistory(historyDirectory);recordHistory=!noHistory;history.enabled(recordHistory);
    this.terminal=terminal;
    var memory=new DefaultHistory(){@Override public void add(java.time.Instant time,String line){if(recordHistory&&history.accepts(line))super.add(time,line);}};
    reader=LineReaderBuilder.builder().terminal(terminal).parser(commandParser()).history(memory).completer(this::complete).build();
    reader.setVariable(LineReader.HISTORY_SIZE,1000);reader.setVariable(LineReader.HISTORY_FILE_SIZE,1000);
    reader.setOpt(LineReader.Option.BRACKETED_PASTE);
    if(java.io.File.separatorChar=='\\')reader.setOpt(LineReader.Option.CASE_INSENSITIVE);
    if(recordHistory)for(String line:history.entries())memory.add(java.time.Instant.now(),line);
    terminal.handle(Terminal.Signal.INT,signal->{backend.detach();attached=false;});
  }
  public void selectInitialProject(Path path)throws IOException,InterruptedException {
    Path selected=(path==null?Path.of(""):path).toAbsolutePath().normalize();
    var value=backend.post("/api/v1/projects/register",Map.of("path",selected.toString()));selectProject(value.path("id").asText());
  }
  private final CompletionEngine paths=new CompletionEngine().register(new FilePathCompletionProvider());
  private void complete(LineReader input,ParsedLine line,List<Candidate> candidates) {
    List<String> values=completionValues(line.words(),line.wordIndex(),line.word());
    for(String value:values)if(value.startsWith(line.word())){
      var candidate=catalog.presentation(line.words(),line.wordIndex(),value);
      candidates.add(new Candidate(candidate.value(),candidate.display(),candidate.kind(),candidate.description(),null,null,candidate.appendSpace()));
    }
    Set<String> types=catalog.pathTypes(line.words(),line.wordIndex(),line.word());
    if(line.words().size()>1&&line.words().getFirst().equals("/project")&&Set.of("register","add","cd").contains(line.words().get(1)))types=Set.of("directory");
    if(!types.isEmpty()) {
      var context=new CompletionContext(line.line(),line.cursor(),line.words(),line.wordIndex(),line.wordIndex()-1,line.word(),List.of(),types,List.of(),projectDirectory==null?Path.of("").toAbsolutePath():projectDirectory);
      for(var value:paths.complete(context))candidates.add(new Candidate(value.value(),value.display(),value.kind(),value.description(),null,null,value.appendSpace()));
    }
  }
  List<String> completionValues(List<String> words,int wordIndex,String prefix) {
    var values=new LinkedHashSet<>(catalog.complete(words,wordIndex,prefix));
    if(wordIndex==0)values.addAll(COMMANDS);
    if(wordIndex==1&&!words.isEmpty()&&words.getFirst().equals("/model"))values.addAll(modelIds);
    String type=catalog.candidateType(words,wordIndex);
    if(type.endsWith("$RegisteredProjectCandidates")||type.endsWith("$ProjectDirectoryCandidates"))values.addAll(projectPaths);
    if(type.endsWith("SessionCompletionCandidates$CurrentProject"))values.addAll(sessionIds);
    else if(type.endsWith("SessionCompletionCandidates"))values.addAll(allSessionIds);
    if(!words.isEmpty()&&words.getFirst().equals("/agent")){if(wordIndex==1)values.addAll(List.of("codex","claude"));if(wordIndex==2&&words.size()>1&&Set.of("codex","claude").contains(words.get(1))){values.addAll(List.of("review","implement","implementation","merge"));if(words.get(1).equals("codex"))values.add("material-review");}}
    if(wordIndex==2&&words.size()>1) {
      if(words.getFirst().equals("/project")&&words.get(1).equals("select"))values.addAll(projectIds);
      if(words.getFirst().equals("/session")&&Set.of("select","switch","resume").contains(words.get(1)))values.addAll(sessionIds);
    }
    return values.stream().filter(value->value.startsWith(prefix)).toList();
  }
  void refreshCatalog()throws IOException,InterruptedException {
    if(catalogLoaded)return;
    try{catalog=new CachedCatalog(BackendClient.JSON.treeToValue(backend.get("/api/v1/cli/catalog"),dev.mikoto2000.rei.application.cli.CliCatalog.class));}
    catch(BackendClient.ApiException missing){if(missing.status()!=404)throw missing;}
    catalogLoaded=true;
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
    refreshCatalog();
    var projects=backend.get("/api/v1/projects");var ids=new ArrayList<String>();var registeredPaths=new ArrayList<String>();for(var value:projects){ids.add(value.path("id").asText());if(value.hasNonNull("path"))registeredPaths.add(value.path("path").asText());}projectIds=List.copyOf(ids);projectPaths=List.copyOf(registeredPaths);
    try{allSessionIds=sessionCompletion(null);}catch(BackendClient.ApiException unavailable){if(unavailable.status()!=400&&unavailable.status()!=404)throw unavailable;}
    if(project!=null)sessionIds=sessionCompletion(project);
  }
  private List<String> sessionCompletion(String project)throws IOException,InterruptedException{
    var ids=new ArrayList<String>();var cursors=new HashSet<String>();String cursor=null;do{String path="/api/v1/sessions?limit=100"+(project==null?"":"&projectId="+BackendClient.segment(project))+(cursor==null?"":"&cursor="+BackendClient.segment(cursor));var page=backend.get(path);for(var value:page.path("items"))ids.add(value.path("sessionId").asText());cursor=page.path("nextCursor").isTextual()?page.path("nextCursor").asText():null;if(cursor!=null&&(!cursors.add(cursor)||cursors.size()>128))throw new IOException("Session completion pagination capacity exceeded");}while(cursor!=null);return List.copyOf(ids);
  }
  private void selectProject(String id)throws IOException,InterruptedException {
    JsonNode match=null;for(var value:backend.get("/api/v1/projects"))if(id.equals(value.path("id").asText()))match=value;
    if(match==null)throw new IllegalArgumentException("Unknown registered Project ID");
    project=id;projectName=match.path("name").asText();projectDirectory=match.path("path").asText().isBlank()?null:Path.of(match.path("path").asText());session=null;refresh();
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
  String sessionId(){return session;}
  private static Parser commandParser(){return new dev.mikoto2000.rei.ui.shell.ShellCompletionParser(Path.of(System.getProperty("user.home")));}
  static List<String> commandWords(String line){return java.util.Arrays.asList(new dev.mikoto2000.rei.core.command.UserInputParser().split(line));}
  void command(String line)throws IOException,InterruptedException {
    var rawWords=commandWords(line);String requestedHelp=catalog.requestedHelp(rawWords);
    if(requestedHelp!=null){if(!requestedHelp.isEmpty())out(requestedHelp);return;}
    var words=catalog.normalize(rawWords);
    String name=words.getFirst();String action=words.size()>1?words.get(1):"list";
    if(catalog.requiresSubcommand(words)){out(catalog.help(words));return;}
    if(words.size()>1&&!words.contains("--")&&Set.of("--help","-h").contains(words.getLast())) {
      String help=catalog.help(words.subList(0,words.size()-1));if(!help.isEmpty()){out(help);return;}
    }
    if(name.equals("/mode")&&session==null) {
      requireProject();
      if(action.equals("list")||action.equals("status")){if(words.size()>2)throw new IllegalArgumentException("Unexpected mode argument");out("Response mode: auto (text: normal, voice: conversation); no active Session");return;}
      // Validate before creating an empty Session. Run access is unchanged.
      RemoteCommands.route(words,project,"validation");createSession(null);
    }
    if(name.equals("/llm")&&words.size()==2&&words.get(1).equals("capture")){out("/llm capture next|status|off|list [run-id]|show <attempt-id>|raw <attempt-id>|export <attempt-id> <path>|delete <run-id>|clear");return;}
    if(name.equals("/llm")&&words.size()==3&&words.get(1).equals("capture")&&words.get(2).equals("next")&&session==null){requireProject();createSession(null);}
    if(RemoteCommands.NAMES.contains(name)&&!(name.equals("/history")&&Set.of("off","on").contains(action))) {
      var request=RemoteCommands.route(words,project,session,projectDirectory==null?Path.of("").toAbsolutePath():projectDirectory);
      if(Set.of("/api/v1/activity/commands/coaching/settings","/api/v1/activity/commands/coaching/enabled").contains(request.path())) {
        var state=backend.get("/api/v1/activity/coaching");if(!state.path("revision").canConvertToLong())throw new java.io.IOException("Coaching revision unavailable");
        var body=new LinkedHashMap<String,Object>();((Map<?,?>)request.body()).forEach((key,value)->body.put((String)key,value));body.put("expectedRevision",state.path("revision").asLong());request=new RemoteCommands.Request(request.method(),request.path(),body);
      }
      if(Set.of("/api/v1/google-calendar/authorize","/api/v1/google-calendar/refresh-token","/api/v1/google-tasks/authorize").contains(request.path())) {
        String key=UUID.randomUUID().toString();out("Receipt: "+key);var accepted=switch(request.path()) {
          case "/api/v1/google-calendar/authorize"->backend.authorizeGoogleCalendar(request.body(),key);
          case "/api/v1/google-calendar/refresh-token"->backend.refreshGoogleCalendar(request.body(),key);
          default->backend.authorizeGoogleTasks(request.body(),key);
        };session=accepted.path("sessionId").asText();run=accepted.path("runId").asText();out("Run: "+run);watch(run);refresh();return;
      }
      if(name.equals("/memory")&&Set.of("summarize","consolidate").contains(action)) {
        String key=UUID.randomUUID().toString();out("Receipt: "+key);
        var accepted=action.equals("summarize")?backend.submitMemorySummary(project,request.body(),key):backend.submitMemoryConsolidation(project,request.body(),key);
        session=accepted.path("sessionId").asText();run=accepted.path("runId").asText();out("Run: "+run);watch(run);refresh();return;
      }
      if(name.equals("/sleep")&&request.method().equals("POST")&&request.path().endsWith("/memory/sleep")) {
        String key=UUID.randomUUID().toString();out("Receipt: "+key);var accepted=backend.submitMemorySleep(project,request.body(),key);
        session=accepted.path("sessionId").asText();run=accepted.path("runId").asText();out("Run: "+run);watch(run);refresh();return;
      }
      if(Set.of("/api/v1/diagnostics","/api/v1/document/recovery","/api/v1/repair/recovery","/api/v1/paper/operations","/api/v1/material-reviews","/api/v1/external-agent/operations").contains(request.path())) {
        String key=UUID.randomUUID().toString();out("Receipt: "+key);var accepted=switch(request.path()) {
          case "/api/v1/document/recovery"->backend.submitDocumentRecovery(request.body(),key);
          case "/api/v1/repair/recovery"->backend.submitRepairRecovery(request.body(),key);
          case "/api/v1/paper/operations"->backend.submitPaper(request.body(),key);
          case "/api/v1/material-reviews"->backend.submitMaterialReview(request.body(),key);
          case "/api/v1/external-agent/operations"->backend.submitExternalAgent(request.body(),key);
          default->backend.submitDiagnostic(request.body(),key);
        };
        session=accepted.path("sessionId").asText();run=accepted.path("runId").asText();out("Run: "+run);watch(run);refresh();return;
      }
      if(name.equals("/llm")&&(request.path().endsWith("/raw")||request.path().endsWith("/export"))) {
        var attempt=backend.get(request.path().substring(0,request.path().lastIndexOf('/')));
        if(!attempt.path("captureState").asText().equals("COMPLETE"))throw new IllegalArgumentException("Complete original unavailable");
        String confirmation=reader.readLine("秘密情報を含む未加工本文を表示・保存します。端末履歴・保存後のコピーは削除対象外です。続行しますか? [y/N] ");
        if(!confirmation.equalsIgnoreCase("y")&&!confirmation.equalsIgnoreCase("yes"))return;
        var body=new LinkedHashMap<String,Object>((Map<String,Object>)request.body());body.put("approved",true);var response=backend.post(request.path(),body);
        // Raw is already control-escaped by CaptureDisplay and was explicitly approved. Do not mask its contents again.
        if(request.path().endsWith("/raw"))reader.printAbove(response.path("text").asText());else out(response.path("text").asText());return;
      }
      var response=request.method().equals("GET")?backend.get(request.path()):backend.mutation(request.method(),request.path(),request.body());
      if(Set.of("/search","/image","/summarize","/briefing","/work","/resume","/embed","/schedule","/task").contains(name)&&response.has("runId")){if(name.equals("/embed"))out("追加処理を開始: "+String.join(", ",words.subList(action.equals("add")?2:1,words.size())));if(response.path("text").isTextual())out(response.path("text").asText());run=response.path("runId").asText();out("Run: "+run);watch(run);return;}
      if(name.equals("/activity")&&request.path().endsWith("/suggest-rules")||name.equals("/bsky")&&request.path().endsWith("/replies")||name.equals("/interest")&&request.path().endsWith("/discover")||name.equals("/feed")&&Set.of("update","summary","item").contains(action)&&response.has("runId")){run=response.path("runId").asText();out("Run: "+run);watch(run);return;}
      if(name.equals("/mode")) {
        String style=response.path("style").asText("AUTO").toLowerCase(Locale.ROOT);
        out("Response mode: "+style+(style.equals("auto")?" (text: normal, voice: conversation)":response.path("voiceOnly").asBoolean()?" (voice only)":" (text and voice)"));return;
      }
      if(name.equals("/models")) {
        var names=new ArrayList<String>();String current=response.path("currentModel").asText();
        for(var model:response.path("models")){String value=model.asText();names.add(value);out((value.equals(current)?"* ":"")+value);}modelIds=List.copyOf(names);return;
      }
      if(name.equals("/model")){out((request.method().equals("GET")?"":"current model: ")+response.path("model").asText());return;}
      if(response.isObject()&&response.path("text").isTextual())out(response.path("text").asText());else show(response);return;
    }
    switch(name) {
      case "/sh"->{if(words.size()!=1)throw new IllegalArgumentException("Usage: /sh");requireProject();if(projectDirectory==null)throw new IllegalArgumentException("Project directory is unavailable");backend.detach();attached=false;int exit=InteractiveShell.run(terminal,projectDirectory);if(exit!=0)out("shell exited with code "+exit);}
      case "/help"->{String help=catalog.help(words.subList(1,words.size()));out(help.isEmpty()?String.join(" ",COMMANDS)+"\n/project list|select ID|register LOCAL_PATH; /session list|select ID|new|end; /watch RUN_ID; /history off|on. Unsupported commands are never sent to the model.":help);}
      case "/project"->{switch(action){
        case "list"->{if(words.size()!=2&&words.size()!=1)throw new IllegalArgumentException("Usage: /project list");for(var value:backend.get("/api/v1/projects"))out((value.path("id").asText().equals(project)?"* ":"  ")+value.path("path").asText());refresh();}
        case "select"->{if(words.size()!=3)throw new IllegalArgumentException("Usage: /project select ID");selectProject(argument(words,2));}
        case "remove"->{if(words.size()!=3)throw new IllegalArgumentException("Usage: /project remove DIR");var target=localProjectPath(words.get(2)).toRealPath();for(var value:backend.get("/api/v1/projects")){if(target.toString().equals(value.path("path").asText())){String removed=value.path("id").asText();backend.mutation("DELETE","/api/v1/projects/"+BackendClient.segment(removed),null);if(removed.equals(project)){var fallback=backend.post("/api/v1/projects/register",Map.of("path",Path.of("").toAbsolutePath().normalize().toString()));selectProject(fallback.path("id").asText());}break;}}out("project removed: "+target);out("current project: "+(projectDirectory==null?Path.of("").toAbsolutePath():projectDirectory));refresh();}
        case "register","add","cd"->{if(words.size()!=3)throw new IllegalArgumentException("Usage: /project "+action+" DIR");var value=backend.post("/api/v1/projects/register",Map.of("path",localProjectPath(argument(words,2)).toString()));if(!action.equals("add")){boolean changed=!value.path("id").asText().equals(project);if(changed||!action.equals("cd"))selectProject(value.path("id").asText());if(action.equals("cd")&&changed){var latest=backend.get("/api/v1/sessions?projectId="+BackendClient.segment(project)+"&limit=1").path("items");if(!latest.isEmpty())session=latest.get(0).path("sessionId").asText();}}out((action.equals("add")?"project added: ":"current project: ")+value.path("path").asText());refresh();}
        default->throw new IllegalArgumentException("Usage: /project list|add DIR|cd DIR|select ID|register LOCAL_PATH");}}

      case "/session"->{
        requireProject();String selectedAction=words.size()==1?"show":action;
        switch(selectedAction) {
          case "list"->{
            if(words.size()!=2)throw new IllegalArgumentException("Usage: /session list");
            var format=new dev.mikoto2000.rei.conversation.HistoryFormatter();String cursor=null;boolean any=false;var listedIds=new ArrayList<String>();
            do {
              var page=backend.get("/api/v1/sessions?projectId="+BackendClient.segment(project)+"&limit=100"+(cursor==null?"":"&cursor="+BackendClient.segment(cursor)));
              for(var row:page.path("items")){listedIds.add(row.path("sessionId").asText());if(!any){out("Sessions:");any=true;}out((row.path("sessionId").asText().equals(session)?"* ":"  ")+format.label(row.path("sessionId").asText())+"  "+format.label(row.path("title").asText()));}
              String next=page.path("nextCursor").isTextual()?page.path("nextCursor").asText():null;
              if(next!=null&&next.equals(cursor))throw new IOException("Session cursor did not advance");cursor=next;
            } while(cursor!=null);
            if(!any)out("No sessions.");
            sessionIds=List.copyOf(listedIds);
          }
          case "show"->{
            if(words.size()>2)throw new IllegalArgumentException("Usage: /session show");
            if(session==null)out("No active session.");else {
              var row=backend.get("/api/v1/sessions/"+BackendClient.segment(session));var format=new dev.mikoto2000.rei.conversation.HistoryFormatter();
              out("Session:");out("  id: "+format.label(row.path("sessionId").asText()));out("  title: "+format.label(row.path("title").asText()));out("  projectId: "+format.label(row.path("projectId").asText()));out("  createdAt: "+row.path("createdAt").asText());out("  updatedAt: "+row.path("updatedAt").asText());
            }
          }
          case "select","switch","resume"->{
            if(words.size()!=3)throw new IllegalArgumentException("Usage: /session switch SESSION_ID");
            String id=argument(words,2);var value=backend.get("/api/v1/sessions/"+BackendClient.segment(id));if(!project.equals(value.path("projectId").asText()))throw new IllegalArgumentException("Session belongs to another project. Select that project first.");session=id;out("Current session: "+new dev.mikoto2000.rei.conversation.HistoryFormatter().label(id));
          }
          case "new"->{if(words.size()>3)throw new IllegalArgumentException("Usage: /session new [TITLE]");createSession(words.size()==3?words.get(2):null);}
          case "end"->{
            if(words.size()!=2)throw new IllegalArgumentException("Usage: /session end");
            if(session==null)out("No active session.");else {backend.post("/api/v1/sessions/"+BackendClient.segment(session)+"/end",Map.of("projectId",project));out("Session ended: "+new dev.mikoto2000.rei.conversation.HistoryFormatter().label(session));session=null;}
          }
          default->throw new IllegalArgumentException("Usage: /session list|show|switch ID|new [TITLE]|end");
        }if(!Set.of("list","show").contains(selectedAction))refresh();
      }
      case "/chat"->{var input=ChatInput.parse(words,mode);if(input.runId()==null)send(input.message(),input.mode());else{requireProject();requireSession();show(backend.post("/api/v1/runs/"+BackendClient.segment(input.runId())+"/input",Map.of("projectId",project,"sessionId",session,"message",input.message())));}}
      case "/run-mode"->{String selected=argument(words,1).toUpperCase(Locale.ROOT).replace('-','_');if(!Set.of("EXCLUSIVE","READ_ONLY","CONVERSATION").contains(selected))throw new IllegalArgumentException("Usage: /run-mode exclusive|read-only|conversation");mode=selected;out("Run access: "+mode);}
      case "/cancel"->{if(words.size()>2)throw new IllegalArgumentException("Usage: /cancel [RUN_ID]");if(words.size()==1){requireProject();backend.post("/api/v1/projects/"+BackendClient.segment(project)+"/runs/cancel",Map.of());}else show(backend.post("/api/v1/runs/"+BackendClient.segment(argument(words,1))+"/cancel",Map.of()));}
      case "/watch"->{run=argument(words,1);watch(run);}
      case "/runs"->{if(words.size()>2)throw new IllegalArgumentException("Usage: /runs [RUN_ID]");if(words.size()==2)show(backend.get("/api/v1/runs/"+BackendClient.segment(words.get(1))));else out(backend.get("/api/v1/runs/active").path("text").asText());}
      case "/receipt"->{String key=argument(words,1);if(!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid receipt key");show(backend.get("/api/v1/chat/receipts/"+key));}
      case "/history","/input-history"->{if(!Set.of("off","on").contains(action))throw new IllegalArgumentException("Usage: /input-history off|on");recordHistory=action.equals("on");history.enabled(recordHistory);out("Input history "+action);}
      default->throw new IllegalArgumentException("Unsupported CLI command: "+name+". Use legacy-shell; this input was not sent to the model.");
    }
  }
  private void createSession(String title)throws IOException,InterruptedException {
    var body=new LinkedHashMap<String,Object>();body.put("projectId",project);body.put("title",title);
    var value=backend.post("/api/v1/sessions",body);session=value.path("sessionId").asText();out("Current session: "+session);
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
  private Path localProjectPath(String value) {
    var path=Path.of(value);return (path.isAbsolute()?path:(projectDirectory==null?Path.of("").toAbsolutePath():projectDirectory).resolve(path)).toAbsolutePath().normalize();
  }
  private static String argument(List<String> words,int index){if(index>=words.size())throw new IllegalArgumentException("Missing command argument");return words.get(index);}
  private void show(JsonNode value)throws IOException{out(BackendClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
  private String safeOutput(String value){return backend.redact(value).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]","");}
  private void out(String value){reader.printAbove(safeOutput(value));}
  @Override public void close()throws IOException {attached=false;backend.detach();streams.shutdownNow();history.close();terminal.close();}
}
