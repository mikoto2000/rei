package dev.mikoto2000.rei.core.completion;

import java.util.Set;

public final class PathCompletionCandidates {
  private PathCompletionCandidates() { }
  public static class File implements CompletionMetadata {
    public Set<String> types(CompletionContext context) { return Set.of(FilePathCompletionProvider.FILE); }
  }
  public static class Directory implements CompletionMetadata {
    public Set<String> types(CompletionContext context) { return Set.of(FilePathCompletionProvider.DIRECTORY); }
  }
  public static class Any implements CompletionMetadata {
    public Set<String> types(CompletionContext context) { return Set.of(FilePathCompletionProvider.BOTH); }
  }
}
