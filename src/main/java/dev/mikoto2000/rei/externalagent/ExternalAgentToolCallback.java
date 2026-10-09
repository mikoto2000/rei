package dev.mikoto2000.rei.externalagent;

import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Trusted application registration, never a model/MCP-provided tool name. */
public final class ExternalAgentToolCallback implements ToolCallback {
  private final ToolCallback delegate;
  ExternalAgentToolCallback(ToolCallback delegate){this.delegate=delegate;}
  public boolean authorizesSpecification(){return Set.of("prepareCodexImplementation","requestCodexImplementation").contains(getToolDefinition().name());}
  public boolean availableAfterDelegation(){return Set.of("requestCodexImplementation","getCodexImplementationRequest","getExternalImplementation","inspectExternalImplementation","recordCodexAcceptanceEvaluation").contains(getToolDefinition().name());}
  @Override public ToolDefinition getToolDefinition(){return delegate.getToolDefinition();}
  @Override public ToolMetadata getToolMetadata(){return delegate.getToolMetadata();}
  @Override public String call(String input){return delegate.call(input);}
  @Override public String call(String input,ToolContext context){return delegate.call(input,context);}
}
