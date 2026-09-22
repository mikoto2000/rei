package dev.mikoto2000.rei.core.completion;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Cursor prefix is decoded by the UI parser. argumentIndex is local to the selected command. */
public record CompletionContext(String fullLine, int cursorPosition, List<String> tokens,
    int wordIndex, int argumentIndex, String currentToken, List<String> commandPath,
    Set<String> types, List<CompletionCandidate> choices, Path workingDirectory) {
  public CompletionContext {
    tokens = List.copyOf(tokens);
    commandPath = List.copyOf(commandPath);
    types = Set.copyOf(types);
    choices = List.copyOf(choices);
  }
  public CompletionContext withTypes(Set<String> selectedTypes) {
    return new CompletionContext(fullLine, cursorPosition, tokens, wordIndex, argumentIndex,
        currentToken, commandPath, selectedTypes, choices, workingDirectory);
  }
}
