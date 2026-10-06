package dev.mikoto2000.rei.externalagent;

import java.util.function.BooleanSupplier;

public interface ExternalAgentExecutor {
  default boolean supportsContinuation() { return false; }
  ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled);
}
