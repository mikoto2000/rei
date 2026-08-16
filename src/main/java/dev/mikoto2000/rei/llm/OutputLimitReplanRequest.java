package dev.mikoto2000.rei.llm;

public record OutputLimitReplanRequest(
    String originalUserRequest,
    String currentGoal,
    String progressSoFar,
    String partialOutput,
    int replanCount,
    int maxReplansPerGoal,
    int remainingLlmCalls) {
}
