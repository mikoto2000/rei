package dev.mikoto2000.rei.subagent;

import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;

/** Local file helpers get transient caches/Working Set, never project-scoped mutable beans. */
@Component
public class SubAgentToolCatalog {
  private final SearchTools knowledge;
  private final WebSearchTools web;
  public SubAgentToolCatalog(SearchTools knowledge, WebSearchTools web) { this.knowledge = knowledge; this.web = web; }
  public List<ToolCallback> createTools() {
    return List.of(MethodToolCallbackProvider.builder().toolObjects(new Tools(), knowledge, web).build().getToolCallbacks());
  }
  public Set<String> knownNames() {
    Set<String> names = new HashSet<>();
    createTools().forEach(callback -> names.add(callback.getToolDefinition().name()));
    names.add("delegateTask");
    return Set.copyOf(names);
  }
}
