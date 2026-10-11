package dev.mikoto2000.rei.cli;

import java.util.*;

/** Fixed public API adapters. No endpoint, shell text, or reflection target is supplied by a command. */
public final class RemoteCommands {
  private static final com.fasterxml.jackson.databind.ObjectMapper COMMAND_JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder(
      com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(32768).maxNumberLength(64).build()).build())
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public record Request(String method,String path,Object body) {}
  public static final Set<String> NAMES=Set.of("/mode","/history","/feed","/skill","/profile","/briefing","/reminder","/interest","/memory","/search","/timer","/tasks","/goal","/attention","/checkpoint","/resume","/approval","/dependency","/work","/summarize","/image","/model","/models");
  public static Request route(List<String> words,String project,String session) {
    String name=words.getFirst(),action=words.size()>1?words.get(1):"list";
    if(!NAMES.contains(name))throw new IllegalArgumentException("Unsupported CLI command: "+name+". Use legacy-shell; this input was not sent to the model.");
    if(name.equals("/models")){exact(words,1);return get("/api/v1/models");}
    if(name.equals("/model")) {
      if(words.size()==1)return get("/api/v1/models/current");
      exact(words,2);String model=words.get(1);
      if(model.isBlank()||model.length()>1024||model.codePoints().anyMatch(Character::isISOControl))throw invalid();
      return new Request("PUT","/api/v1/models/current",Map.of("model",model));
    }
    if(name.equals("/profile")||name.equals("/briefing")){exact(words,1);return get("/api/v1/"+name.substring(1));}
    if(name.equals("/search")){return post("/api/v1/search",Map.of("query",text(words,1)));}
    if(name.equals("/memory")&&project!=null&&Set.of("list","search","show","forget").contains(action)) {
      String base="/api/v1/projects/"+id(project)+"/memory";
      if(action.equals("list")){var selected=options(words,words.size()==1?1:2,Set.of("--limit","--offset"),Set.of());return get(base+"?limit="+integer(selected.getOrDefault("--limit","20"))+"&offset="+integer(selected.getOrDefault("--offset","0")));}
      if(action.equals("search")){var selected=parse(words,2,Set.of("--limit"),Set.of());if(selected.arguments().isEmpty())throw invalid();return get(base+"/search?query="+BackendClient.segment(String.join(" ",selected.arguments()))+"&limit="+integer(selected.options().getOrDefault("--limit","10")));}
      exact(words,3);return action.equals("show")?get(base+"/"+id(words.get(2))):post(base+"/"+id(words.get(2))+"/forget",Map.of());
    }
    if(name.equals("/history")) {
      if(action.equals("list")){exact(words,words.size()==1?1:2);return get("/api/v1/sessions"+(project==null?"":"?projectId="+id(project)));}
      if(action.equals("show")){if(words.size()>3)throw invalid();String selected=words.size()==3?words.get(2):session;if(selected==null)throw new IllegalArgumentException("Select a Session or provide /history show SESSION_ID");return get("/api/v1/sessions/"+id(selected)+"/turns");}
      throw unsupported();
    }
    if(Set.of("/feed","/skill","/reminder","/interest","/memory").contains(name)) {
      String resource=switch(name){case "/skill"->"skills";case "/reminder"->"reminders";case "/interest"->"interests";case "/memory"->"memories";default->"feed";};String base="/api/v1/"+resource;
      if(name.equals("/interest")&&action.equals("list")) {
        var selected=options(words,2,Set.of("--hours"),Set.of());long hours=revision(selected.getOrDefault("--hours","24"));
        if(hours<1||hours>8760)throw invalid();return get(base+"?hours="+hours);
      }
      if(action.equals("list")){exact(words,words.size()==1?1:2);return get(base);}
      if(action.equals("show")){exact(words,3);return get(base+"/"+id(words.get(2)));}
      if(action.equals("delete")&&Set.of("/feed","/reminder","/memory").contains(name)){if(words.size()!=3&&(words.size()!=4||!words.get(3).equals("--yes")))throw invalid();return new Request("DELETE",base+"/"+id(words.get(2)),null);}
      if(name.equals("/skill")&&action.equals("reload")){exact(words,2);return post(base+"/reload",Map.of());}
      if(name.equals("/feed")&&Set.of("enable","disable").contains(action)){exact(words,3);return new Request("PATCH",base+"/"+id(words.get(2)),Map.of("enabled",action.equals("enable")));}
      if(name.equals("/feed")&&action.equals("add")) {
        var parsed=parse(words,2,Set.of("--name"),Set.of());var args=parsed.arguments();var selected=parsed.options();
        if(args.size()!=1&&(args.size()!=2||!selected.isEmpty()))throw invalid();
        var body=new LinkedHashMap<String,Object>();body.put("url",args.getFirst());body.put("displayName",args.size()==2?args.get(1):selected.get("--name"));return post(base,body);
      }
      if(name.equals("/feed")&&action.equals("edit")) {
        var parsed=parse(words,2,Set.of("--name"),Set.of("--enabled","--disabled"));if(parsed.arguments().size()!=1)throw invalid();
        boolean enabled=Boolean.parseBoolean(parsed.options().get("--enabled")),disabled=Boolean.parseBoolean(parsed.options().get("--disabled"));
        var body=new LinkedHashMap<String,Object>();body.put("displayName",parsed.options().get("--name"));body.put("enabled",enabled==disabled?null:enabled);
        return new Request("PATCH",base+"/"+id(parsed.arguments().getFirst()),body);
      }
      if(name.equals("/reminder")&&action.equals("add")) {
        var parsed=parse(words,2,Set.of("--at","--target","--minutes-before"),Set.of());var selected=parsed.options();
        if(selected.isEmpty()&&parsed.arguments().size()==2)return post(base,Map.of("message",parsed.arguments().getFirst(),"at",parsed.arguments().get(1)));
        if(parsed.arguments().isEmpty()||!(selected.containsKey("--at")&&selected.size()==1||!selected.containsKey("--at")&&selected.containsKey("--target")&&selected.containsKey("--minutes-before")&&selected.size()==2))throw invalid();
        var body=new LinkedHashMap<String,Object>();body.put("message",String.join(" ",parsed.arguments()));
        String at=selected.get("--at"),target=selected.get("--target");
        try {java.time.OffsetDateTime.parse(at==null?target:at);}catch(java.time.format.DateTimeParseException malformed){throw invalid();}
        body.put("at",at);body.put("target",target);body.put("minutesBefore",selected.containsKey("--minutes-before")?revision(selected.get("--minutes-before")):null);return post(base,body);
      }
      if(name.equals("/memory")&&action.equals("add")){exact(words,5);return post(base,Map.of("content",words.get(2),"type",words.get(3),"scope",words.get(4)));}
      throw unsupported();
    }
    if(project==null)throw new IllegalArgumentException("Select a Project first");
    if(name.equals("/mode")) {
      if(session==null)throw new IllegalArgumentException("Select a Session first");
      String base="/api/v1/sessions/"+id(session)+"/response-style";
      if(action.equals("list")||action.equals("status")){exact(words,words.size()==1?1:2);return get(base+"?projectId="+id(project));}
      if(!Set.of("auto","normal","conversation").contains(action))throw unsupported();
      boolean voiceOnly=words.size()==3&&words.get(2).equals("--voice-only")&&action.equals("conversation");
      exact(words,voiceOnly?3:2);
      return new Request("PATCH",base,Map.of("projectId",project,"style",action.toUpperCase(Locale.ROOT),"voiceOnly",voiceOnly));
    }
    String scoped="/api/v1/projects/"+id(project);
    if(name.equals("/tasks")) {
      if(action.equals("list")){exact(words,words.size()==1?1:2);return get("/api/v1/tasks?projectId="+id(project));}
      if(action.equals("show")){exact(words,3);return get("/api/v1/tasks/"+id(words.get(2))+"?projectId="+id(project));}
      if(Set.of("cancel","resume","suspend").contains(action)) {
        exact(words,5);var body=new LinkedHashMap<String,Object>();body.put("projectId",project);body.put("sessionId",session);body.put("expectedRunId",words.get(3));body.put("expectedRevision",revision(words.get(4)));
        return post("/api/v1/tasks/"+id(words.get(2))+"/"+action,body);
      }
      throw unsupported();
    }
    if(name.equals("/resume")){exact(words,2);return post(scoped+"/checkpoints/"+id(action)+"/resume",Map.of());}
    if(name.equals("/summarize")){exact(words,2);return post("/api/v1/summaries",Map.of("projectId",project,"url",action));}
    if(name.equals("/image")){return post("/api/v1/images",Map.of("projectId",project,"prompt",text(words,1)));}
    if(name.equals("/work")) {
      if(Set.of("show","list").contains(action)){exact(words,words.size()==1?1:2);return get(scoped+"/work-context");}
      if(Set.of("summary","history").contains(action)){exact(words,2);return get(scoped+"/work-context/"+action);}
      if(action.equals("update")){exact(words,2);if(session==null)throw new IllegalArgumentException("Select a Session first");return post("/api/v1/sessions/"+id(session)+"/work-context/update",Map.of());}
      throw unsupported();
    }
    String resource=switch(name){case "/checkpoint"->"checkpoints";case "/approval"->"approvals";case "/dependency"->"dependencies";case "/attention"->"attention";case "/timer"->"schedules";case "/goal"->"goals";default->throw unsupported();};String base=scoped+"/"+resource;
    if(action.equals("list")){exact(words,words.size()==1?1:2);return get(base);}
    if(action.equals("show")){exact(words,3);return get(base+"/"+id(words.get(2)));}
    if(action.equals("history")&&Set.of("/goal","/timer","/dependency").contains(name)){exact(words,3);return get(base+"/"+id(words.get(2))+"/history");}
    if(name.equals("/goal")&&action.equals("create")) {
      if(words.size()<3||session==null)throw invalid();
      var options=options(words,3,Set.of("--file","--sha256","--criteria-json","--completion-json","--max-runs","--max-llm-calls"),Set.of());
      if(options.containsKey("--criteria-json")&&(options.containsKey("--file")||options.containsKey("--sha256")))throw invalid();
      if(!options.containsKey("--criteria-json")&&(!options.containsKey("--file")||!options.containsKey("--sha256")))throw invalid();
      long runs=revision(options.getOrDefault("--max-runs","3")),calls=revision(options.getOrDefault("--max-llm-calls","20"));
      if(runs<1||runs>10||calls<1||calls>100)throw invalid();
      var body=new LinkedHashMap<String,Object>();body.put("sessionId",session);body.put("objective",words.get(2));body.put("maxRuns",runs);body.put("maxLlmCalls",calls);
      for(String option:List.of("--file","--sha256","--criteria-json","--completion-json"))if(options.containsKey(option)) {
        String field=switch(option){case "--criteria-json"->"criteriaJson";case "--completion-json"->"completionJson";default->option.substring(2);};
        body.put(field,options.get(option));
      }
      return post(base,body);
    }
    if(name.equals("/goal")&&action.equals("completion")) {
      if(words.size()<3)throw invalid();
      var options=options(words,3,Set.of("--completion-json","--proof-json"),Set.of());
      if(options.size()!=1)throw invalid();
      String json=options.values().iterator().next();if(json.length()>32768)throw invalid();
      try {
        var body=COMMAND_JSON.readTree(json);if(body==null||!body.isObject())throw invalid();
        boolean definition=options.containsKey("--completion-json");
        return new Request(definition?"PUT":"POST",base+"/"+id(words.get(2))+(definition?"/completion":"/completion-evidence"),body);
      }catch(java.io.IOException malformed){throw invalid();}
    }
    if(name.equals("/checkpoint")&&action.equals("inspect")){exact(words,3);return get(base+"/"+id(words.get(2))+"/reconciliation");}
    if(name.equals("/checkpoint")&&action.equals("save")){exact(words,2);if(session==null)throw new IllegalArgumentException("Select a Session first");return post(base,Map.of("sessionId",session));}
    if(name.equals("/checkpoint")&&action.equals("abandon")){exact(words,3);return post(base+"/"+id(words.get(2))+"/abandon",Map.of());}
    if(name.equals("/approval")&&Set.of("approve","deny","reject").contains(action)){exact(words,3);return post(base+"/"+id(words.get(2))+"/decision",Map.of("approved",action.equals("approve")));}
    if(Set.of("/goal","/timer").contains(name)&&action.equals("reconcile")) {
      if(words.size()<3)throw invalid();
      var options=options(words,3,Set.of("--run-id"),Set.of("--acknowledge-uncertain-side-effects"));
      String run=options.get("--run-id");
      if(run==null||run.isBlank()||run.length()>128||!Boolean.parseBoolean(options.get("--acknowledge-uncertain-side-effects")))throw invalid();
      return post(base+"/"+id(words.get(2))+"/reconcile",Map.of("expectedRunId",run,"acknowledgeUncertainSideEffects",true));
    }
    if(name.equals("/goal")&&action.equals("wait")) {
      if(words.size()<3)throw invalid();
      var options=options(words,3,Set.of("--dependency-id","--wait-reason"),Set.of());
      String path=base+"/"+id(words.get(2))+"/wait";
      if(!options.containsKey("--dependency-id")) {
        if(!options.isEmpty())throw invalid();
        return get(path);
      }
      if(session==null)throw new IllegalArgumentException("Select the owning Goal Session first");
      return post(path,Map.of("dependencyId",options.get("--dependency-id"),"reason",options.getOrDefault("--wait-reason","dependency_wait"),"sessionId",session));
    }
    if(name.equals("/goal")&&Set.of("wait-activate","wait-resume","wait-cancel").contains(action)) {
      if(words.size()<3)throw invalid();
      var options=options(words,3,Set.of("--wait-version"),Set.of());
      if(!options.containsKey("--wait-version"))throw invalid();
      long version=revision(options.get("--wait-version"));if(version<1)throw invalid();
      return post(base+"/"+id(words.get(2))+"/wait/"+action.substring(5),Map.of("expectedVersion",version));
    }
    if(name.equals("/attention")&&action.equals("ack")){exact(words,3);return post(base+"/"+id(words.get(2))+"/ack",Map.of());}
    if(name.equals("/attention")&&action.equals("delivery")){exact(words,3);return get(base+"/"+id(words.get(2))+"/delivery");}
    if(name.equals("/attention")&&Set.of("deliver","retry-delivery").contains(action)) {
      if(words.size()<3)throw invalid();
      boolean retry=action.equals("retry-delivery");
      var options=options(words,3,Set.of(),retry?Set.of("--acknowledge-duplicate-risk"):Set.of());
      return post(base+"/"+id(words.get(2))+"/delivery",Map.of("retry",retry,"acknowledgeDuplicateRisk",Boolean.parseBoolean(options.get("--acknowledge-duplicate-risk"))));
    }
    if(name.equals("/goal")&&Set.of("run","cancel","verify").contains(action)||name.equals("/timer")&&Set.of("activate","cancel").contains(action)){exact(words,3);return post(base+"/"+id(words.get(2))+"/"+action,Map.of());}
    if(name.equals("/goal")&&action.equals("progress")){exact(words,3);return get(base+"/"+id(words.get(2))+"/completion-progress");}
    if(name.equals("/dependency")&&action.equals("answer")){exact(words,5);return post(base+"/"+id(words.get(2))+"/answer",Map.of("expectedVersion",revision(words.get(3)),"answer",words.get(4)));}
    throw unsupported();
  }
  private static String text(List<String> words,int start){if(words.size()<=start)throw invalid();return String.join(" ",words.subList(start,words.size()));}
  private static Map<String,String> options(List<String> words,int start,Set<String> values,Set<String> flags) {
    var parsed=parse(words,start,values,flags);if(!parsed.arguments().isEmpty())throw invalid();return parsed.options();
  }
  private record Parsed(List<String> arguments,Map<String,String> options) {}
  private static Parsed parse(List<String> words,int start,Set<String> values,Set<String> flags) {
    var parsed=new HashMap<String,String>();
    var arguments=new ArrayList<String>();
    for(int index=start;index<words.size();index++) {
      String option=words.get(index);
      if(option.equals("--")){arguments.addAll(words.subList(index+1,words.size()));break;}
      if(!option.startsWith("--")){arguments.add(option);continue;}
      String attached=null;int equals=option.indexOf('=');if(equals>=0){attached=option.substring(equals+1);option=option.substring(0,equals);}
      if(parsed.containsKey(option))throw invalid();
      if(flags.contains(option)){if(attached!=null&&!Set.of("true","false").contains(attached))throw invalid();parsed.put(option,attached==null?"true":attached);continue;}
      if(!values.contains(option))throw invalid();
      if(attached==null){if(++index>=words.size())throw invalid();attached=words.get(index);}
      if(attached.isBlank()||attached.startsWith("--"))throw invalid();parsed.put(option,attached);
    }
    return new Parsed(List.copyOf(arguments),Map.copyOf(parsed));
  }
  private static long revision(String value){try{long revision=Long.parseLong(value);if(revision<0)throw invalid();return revision;}catch(NumberFormatException error){throw invalid();}}
  private static int integer(String value){try{return Integer.parseInt(value);}catch(NumberFormatException error){throw invalid();}}
  private static void exact(List<String> words,int size){if(words.size()!=size)throw invalid();}
  private static String id(String value){if(value==null||value.isBlank()||value.length()>256)throw invalid();return BackendClient.segment(value);}
  private static Request get(String path){return new Request("GET",path,null);}
  private static Request post(String path,Object body){return new Request("POST",path,body);}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid command arguments; inspect /help and docs/lightweight-cli-client.md");}
  private static IllegalArgumentException unsupported(){return new IllegalArgumentException("This operation is not yet available in CLI; use legacy-shell. This input was not sent to the model.");}
}
