package dev.mikoto2000.rei.externalagent;

import java.util.function.BooleanSupplier;
import dev.mikoto2000.rei.llm.ModelCallBudget;

/** Provider selection is an authorized domain request, never an arbitrary executable argument. */
public final class RoutingExternalAgentExecutor implements ExternalAgentExecutor {
 private final ExternalAgentExecutor codex,claude;
 public RoutingExternalAgentExecutor(ExternalAgentExecutor codex,ExternalAgentExecutor claude){this.codex=codex;this.claude=claude;}
 private ExternalAgentExecutor select(ExternalAgentRequest request){return switch(request.agent()){case CODEX->codex;case CLAUDE->claude;};}
 @Override public ExternalAgentResult execute(ExternalAgentRequest request,BooleanSupplier cancelled){return select(request).execute(request,cancelled);}
 @Override public ExternalAgentResult execute(ExternalAgentRequest request,BooleanSupplier cancelled,ModelCallBudget budget){return select(request).execute(request,cancelled,budget);}
 @Override public boolean supportsContinuation(){return codex.supportsContinuation();}
}
