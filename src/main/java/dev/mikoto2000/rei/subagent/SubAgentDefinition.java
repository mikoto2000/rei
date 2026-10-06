package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Provider-independent, immutable configuration for an ephemeral execution. */
public record SubAgentDefinition(String id, String name, String description, String systemPrompt,
    List<String> requestedTools, String model, int maxSteps, Duration timeout, Path source, SubAgentResultSchema resultSchema,
    List<String> evidenceTools, int maxRepairs,List<SubAgentRequiredCall> requiredToolCalls,boolean semanticValidation,boolean inheritApprovals) {
  public SubAgentDefinition(String id,String name,String description,String systemPrompt,List<String> requestedTools,
      String model,int maxSteps,Duration timeout,Path source,SubAgentResultSchema resultSchema,List<String> evidenceTools,
      int maxRepairs,List<SubAgentRequiredCall> requiredToolCalls,boolean semanticValidation) {
    this(id,name,description,systemPrompt,requestedTools,model,maxSteps,timeout,source,resultSchema,evidenceTools,maxRepairs,requiredToolCalls,semanticValidation,false);
  }
  public SubAgentDefinition(String id,String name,String description,String systemPrompt,List<String> requestedTools,
      String model,int maxSteps,Duration timeout,Path source,SubAgentResultSchema resultSchema,List<String> evidenceTools,
      int maxRepairs,List<SubAgentRequiredCall> requiredToolCalls) {
    this(id,name,description,systemPrompt,requestedTools,model,maxSteps,timeout,source,resultSchema,evidenceTools,maxRepairs,requiredToolCalls,false);
  }
  public SubAgentDefinition(String id,String name,String description,String systemPrompt,List<String> requestedTools,
      String model,int maxSteps,Duration timeout,Path source,SubAgentResultSchema resultSchema,List<String> evidenceTools,int maxRepairs) {
    this(id,name,description,systemPrompt,requestedTools,model,maxSteps,timeout,source,resultSchema,evidenceTools,maxRepairs,List.of());
  }
  public SubAgentDefinition(String id, String name, String description, String systemPrompt,
      List<String> requestedTools, String model, int maxSteps, Duration timeout, Path source, SubAgentResultSchema resultSchema,
      List<String> evidenceTools) {
    this(id,name,description,systemPrompt,requestedTools,model,maxSteps,timeout,source,resultSchema,evidenceTools,0);
  }
  public SubAgentDefinition(String id, String name, String description, String systemPrompt,
      List<String> requestedTools, String model, int maxSteps, Duration timeout, Path source, SubAgentResultSchema resultSchema) {
    this(id,name,description,systemPrompt,requestedTools,model,maxSteps,timeout,source,resultSchema,List.of());
  }
  public SubAgentDefinition(String id, String name, String description, String systemPrompt,
      List<String> requestedTools, String model, int maxSteps, Duration timeout, Path source) {
    this(id, name, description, systemPrompt, requestedTools, model, maxSteps, timeout, source, null);
  }
  public SubAgentDefinition {
    if (maxRepairs < 0 || maxRepairs > 3) throw new IllegalArgumentException("maxRepairs: expected integer from 0 to 3");
    if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("id: expected [a-z][a-z0-9-]{0,63}");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name: required");
    if (description == null || description.isBlank()) throw new IllegalArgumentException("description: required");
    if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("systemPrompt: required");
    if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps: must be positive");
    if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout: must be positive");
    try { timeout.toNanos(); } catch (ArithmeticException e) { throw new IllegalArgumentException("timeout: too large"); }
    requestedTools = List.copyOf(requestedTools);
    evidenceTools = List.copyOf(evidenceTools);
    if(semanticValidation&&evidenceTools.isEmpty())throw new IllegalArgumentException("semanticValidation: requires evidenceTools");
    requiredToolCalls=List.copyOf(requiredToolCalls);
    if(requiredToolCalls.size()>16)
      throw new IllegalArgumentException("requiredToolCalls: at most 16 calls using evidenceTools");
    for(var call:requiredToolCalls)if(!evidenceTools.contains(call.tool()))
      throw new IllegalArgumentException("requiredToolCalls: calls must use evidenceTools");
    if (evidenceTools.size()>16 || new java.util.HashSet<>(evidenceTools).size()!=evidenceTools.size()
        || !requestedTools.containsAll(evidenceTools)) throw new IllegalArgumentException("evidenceTools: expected unique subset of tools, at most 16");
    source = source.toAbsolutePath().normalize();
  }
}
