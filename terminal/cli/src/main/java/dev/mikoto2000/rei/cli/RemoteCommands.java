package dev.mikoto2000.rei.cli;

import java.util.*;

/** Fixed public API adapters. No endpoint, shell text, or reflection target is supplied by a command. */
public final class RemoteCommands {
  public record Request(String method,String path,Object body) {}
  public static final Set<String> NAMES=Set.of("/mode","/history","/feed","/skill","/profile","/briefing","/reminder","/interest","/memory","/search","/timer","/tasks","/goal","/attention","/checkpoint","/resume","/approval","/dependency","/work","/summarize","/image");
  public static Request route(List<String> words,String project,String session) {
    String name=words.getFirst(),action=words.size()>1?words.get(1):"list";
    if(!NAMES.contains(name))throw new IllegalArgumentException("Unsupported CLI command: "+name+". Use legacy-shell; this input was not sent to the model.");
    if(name.equals("/profile")||name.equals("/briefing")){exact(words,1);return get("/api/v1/"+name.substring(1));}
    if(name.equals("/search")){return post("/api/v1/search",Map.of("query",text(words,1)));}
    if(name.equals("/history")) {
      if(action.equals("list")){exact(words,words.size()==1?1:2);return get("/api/v1/sessions"+(project==null?"":"?projectId="+id(project)));}
      if(action.equals("show")){if(words.size()>3)throw invalid();String selected=words.size()==3?words.get(2):session;if(selected==null)throw new IllegalArgumentException("Select a Session or provide /history show SESSION_ID");return get("/api/v1/sessions/"+id(selected)+"/turns");}
      throw unsupported();
    }
    if(Set.of("/feed","/skill","/reminder","/interest","/memory").contains(name)) {
      String resource=switch(name){case "/skill"->"skills";case "/reminder"->"reminders";case "/interest"->"interests";case "/memory"->"memories";default->"feed";};String base="/api/v1/"+resource;
      if(action.equals("list")){exact(words,words.size()==1?1:2);return get(base);}
      if(action.equals("show")){exact(words,3);return get(base+"/"+id(words.get(2)));}
      if(action.equals("delete")&&Set.of("/feed","/reminder","/memory").contains(name)){exact(words,4);if(!words.get(3).equals("--yes"))throw new IllegalArgumentException("Deletion requires --yes");return new Request("DELETE",base+"/"+id(words.get(2)),null);}
      if(name.equals("/skill")&&action.equals("reload")){exact(words,2);return post(base+"/reload",Map.of());}
      if(name.equals("/feed")&&Set.of("enable","disable").contains(action)){exact(words,3);return new Request("PATCH",base+"/"+id(words.get(2)),Map.of("enabled",action.equals("enable")));}
      if(name.equals("/feed")&&action.equals("add")){exact(words,4);return post(base,Map.of("url",words.get(2),"displayName",words.get(3)));}
      if(name.equals("/reminder")&&action.equals("add")){exact(words,4);return post(base,Map.of("message",words.get(2),"at",words.get(3)));}
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
    if(name.equals("/checkpoint")&&action.equals("inspect")){exact(words,3);return get(base+"/"+id(words.get(2))+"/reconciliation");}
    if(name.equals("/checkpoint")&&action.equals("save")){exact(words,2);if(session==null)throw new IllegalArgumentException("Select a Session first");return post(base,Map.of("sessionId",session));}
    if(name.equals("/checkpoint")&&action.equals("abandon")){exact(words,3);return post(base+"/"+id(words.get(2))+"/abandon",Map.of());}
    if(name.equals("/approval")&&Set.of("approve","reject").contains(action)){exact(words,3);return post(base+"/"+id(words.get(2))+"/decision",Map.of("approved",action.equals("approve")));}
    if(name.equals("/attention")&&action.equals("ack")){exact(words,3);return post(base+"/"+id(words.get(2))+"/ack",Map.of());}
    if(name.equals("/goal")&&Set.of("run","cancel","verify").contains(action)||name.equals("/timer")&&Set.of("activate","cancel").contains(action)){exact(words,3);return post(base+"/"+id(words.get(2))+"/"+action,Map.of());}
    if(name.equals("/goal")&&action.equals("progress")){exact(words,3);return get(base+"/"+id(words.get(2))+"/completion-progress");}
    if(name.equals("/dependency")&&action.equals("answer")){exact(words,5);return post(base+"/"+id(words.get(2))+"/answer",Map.of("expectedVersion",revision(words.get(3)),"answer",words.get(4)));}
    throw unsupported();
  }
  private static String text(List<String> words,int start){if(words.size()<=start)throw invalid();return String.join(" ",words.subList(start,words.size()));}
  private static long revision(String value){try{long revision=Long.parseLong(value);if(revision<0)throw invalid();return revision;}catch(NumberFormatException error){throw invalid();}}
  private static void exact(List<String> words,int size){if(words.size()!=size)throw invalid();}
  private static String id(String value){if(value==null||value.isBlank()||value.length()>256)throw invalid();return BackendClient.segment(value);}
  private static Request get(String path){return new Request("GET",path,null);}
  private static Request post(String path,Object body){return new Request("POST",path,body);}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid command arguments; inspect /help and docs/lightweight-cli-client.md");}
  private static IllegalArgumentException unsupported(){return new IllegalArgumentException("This operation is not yet available in CLI; use legacy-shell. This input was not sent to the model.");}
}
