package dev.mikoto2000.rei.subagent;

import java.util.*;

/** Declarative child execution dependencies, separate from Waiting conditions. No code predicates. */
public record SubAgentDagSpec(List<Node> nodes,FailurePolicy failurePolicy,int maxConcurrency) {
  public enum FailurePolicy { FAIL_FAST, CONTINUE_INDEPENDENT }
  public record Node(String id,String agent,String task,String context,List<String> dependencies) {
    public Node {
      if(id==null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || agent==null || !agent.matches("[a-z][a-z0-9-]{0,63}")
          || task==null || task.isBlank() || task.length()>4096 || context!=null && context.length()>4096
          || dependencies==null || dependencies.size()>8 || dependencies.stream().anyMatch(value->value==null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
          || new HashSet<>(dependencies).size()!=dependencies.size() || dependencies.contains(id))throw new IllegalArgumentException("Invalid bounded child DAG node");
      dependencies=List.copyOf(dependencies);
    }
  }
  public SubAgentDagSpec {
    if(nodes==null || nodes.isEmpty() || nodes.size()>16 || failurePolicy==null || maxConcurrency<1 || maxConcurrency>2)throw new IllegalArgumentException("Expected 1 to 16 DAG nodes and 1 to 2 workers");
    nodes=List.copyOf(nodes);var byId=new LinkedHashMap<String,Node>();int size=0;
    for(var node:nodes){if(node==null || byId.putIfAbsent(node.id(),node)!=null)throw new IllegalArgumentException("Duplicate child DAG node");size+=node.task().length()+Objects.toString(node.context(),"").length();}
    if(size>32768)throw new IllegalArgumentException("Child DAG input exceeds bounded limit");
    if(nodes.stream().anyMatch(node->!byId.keySet().containsAll(node.dependencies())))throw new IllegalArgumentException("Unknown child DAG dependency");
    var visited=new HashSet<String>();boolean progress;
    do {progress=false;for(var node:nodes)if(!visited.contains(node.id()) && visited.containsAll(node.dependencies())){visited.add(node.id());progress=true;}}while(progress);
    if(visited.size()!=nodes.size())throw new IllegalArgumentException("Child DAG contains a cycle");
  }
  public List<String> ready(Set<String> completed,Set<String> failed) {
    var ids=nodes.stream().map(Node::id).collect(java.util.stream.Collectors.toSet());
    if(completed==null || failed==null || !ids.containsAll(completed) || !ids.containsAll(failed) || !Collections.disjoint(completed,failed))throw new IllegalArgumentException("Invalid child DAG result inventory");
    if(failurePolicy==FailurePolicy.FAIL_FAST && !failed.isEmpty())return List.of();
    return nodes.stream().filter(node->!completed.contains(node.id()) && !failed.contains(node.id()) && completed.containsAll(node.dependencies())).limit(maxConcurrency).map(Node::id).toList();
  }
}
