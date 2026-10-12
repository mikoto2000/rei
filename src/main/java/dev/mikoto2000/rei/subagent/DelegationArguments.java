package dev.mikoto2000.rei.subagent;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure preflight: no approval creation, child dispatch or argument regeneration. */
public final class DelegationArguments {
  private DelegationArguments() { }
  public static String optional(String value) { return value == null || value.isBlank() ? null : value; }
  public static void validate(String tool, String input) {
    var problems=problems(tool,input);
    if(!problems.isEmpty())throw new IllegalArgumentException("Invalid delegation arguments; correct all listed problems together:\n"+String.join("\n",problems));
  }
  public static List<String> problems(String tool,String input) {
    if (!Set.of("delegateTask", "delegateTasks").contains(tool)) return List.of();
    JsonNode root;
    try { root = JsonMapper.builder().build().readTree(input); }
    catch (RuntimeException error) { return List.of("arguments: expected a JSON object"); }
    if (root == null || !root.isObject()) return List.of("arguments: expected a JSON object");
    var errors=new ArrayList<String>();
    if (tool.equals("delegateTask")) { request(root, false, new HashSet<>(),errors,"request"); return List.copyOf(errors); }
    var requests = root.path("requests");
    if (!requests.isArray() || requests.isEmpty() || requests.size() > 8)
      return List.of("requests: must contain 1 to 8 items");
    var ids = new HashSet<String>();
    int index=0;for (var request : requests) request(request, true, ids,errors,"requests["+(index++)+"]");
    return List.copyOf(errors);
  }
  private static void request(JsonNode request, boolean batch, Set<String> ids,List<String> errors,String prefix) {
    if (!request.isObject()) {errors.add(prefix+": must be an object");return;}
    if (batch) {
      String id = text(request, "id",errors,prefix);
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id))
        errors.add(prefix+".id: must be unique and contain 1 to 64 letters, digits, underscores or hyphens");
    }
    String agent = text(request,"agent",errors,prefix), task = text(request,"task",errors,prefix), context = text(request,"context",errors,prefix);
    if(agent==null)errors.add(prefix+".agent: required");
    if(task==null)errors.add(prefix+".task: required");
    else if(task.length()>16384)errors.add(prefix+".task: exceeds 16384 characters");
    if(context!=null&&context.length()>32768)errors.add(prefix+".context: exceeds 32768 characters");
    String backend = text(request,"backendId",errors,prefix);
    if (backend != null && !Set.of("local", "chatgpt-plan", "codex-app-server").contains(backend))
      errors.add(prefix+".backendId: must be local, chatgpt-plan or codex-app-server; omit for local execution");
    boolean local = backend == null || backend.equals("local");
    String account=text(request,"accountRef",errors,prefix),model=text(request,"modelId",errors,prefix),expected=text(request,"expectedOutput",errors,prefix);
    boolean sources=array(request,"sourceRefs",16,4096,errors,prefix);
    boolean constraints=array(request,"constraints",16,2048,errors,prefix);
    boolean criteria=array(request,"completionCriteria",16,2048,errors,prefix);
    if(local) {
      if(account!=null)errors.add(prefix+".accountRef: omit for local delegation");
      if(model!=null)errors.add(prefix+".modelId: omit for local delegation");
      if(sources)errors.add(prefix+".sourceRefs: put target files in task/context for local delegation");
      if(expected!=null)errors.add(prefix+".expectedOutput: put output requirements in task/context for local delegation");
      if(constraints)errors.add(prefix+".constraints: put requirements in task/context for local delegation");
      if(criteria)errors.add(prefix+".completionCriteria: put requirements in task/context for local delegation");
    } else {
      if(context!=null)errors.add(prefix+".context: omit for external delegation; move its content into task");
      if(account==null)errors.add(prefix+".accountRef: ACCOUNT_REQUIRED; specify the allowed account label from current host guidance");
      if(model==null)errors.add(prefix+".modelId: MODEL_REQUIRED; specify the allowed model slug from current host guidance");
      if(!sources)errors.add(prefix+".sourceRefs: nonempty Project-relative paths required (at most 16)");
      if(expected==null)errors.add(prefix+".expectedOutput: nonempty literal result conditions required");
      else try{ExpectedOutputConditions.validate(expected,null);}catch(IllegalArgumentException invalid){errors.add(prefix+".expectedOutput: "+invalid.getMessage());}
    }
  }
  private static String text(JsonNode request,String field,List<String> errors,String prefix) {
    try{return text(request,field);}catch(IllegalArgumentException invalid){errors.add(prefix+"."+field+": must be a string");return null;}
  }
  private static boolean array(JsonNode request,String field,int max,int length,List<String> errors,String prefix) {
    try{return array(request,field,max,length);}catch(IllegalArgumentException invalid){errors.add(prefix+"."+field+": must be an array of at most "+max+" nonempty strings (each at most "+length+" characters)");return false;}
  }
  private static String text(JsonNode request, String field) {
    var value = request.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isString()) throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must be a string");
    return optional(value.asString());
  }
  private static boolean array(JsonNode request, String field, int max, int length) {
    var value = request.path(field);
    if (value.isMissingNode() || value.isNull()) return false;
    if (!value.isArray() || value.size() > max) throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must be a bounded array");
    for (var item : value) if (!item.isString() || item.asString().isBlank() || item.asString().length() > length)
      throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must contain nonempty bounded strings");
    return !value.isEmpty();
  }
}
