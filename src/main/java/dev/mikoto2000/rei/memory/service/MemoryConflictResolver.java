package dev.mikoto2000.rei.memory.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.memory.model.Memory;

@Component
public class MemoryConflictResolver {

  public enum ConflictType {
    NONE,
    DUPLICATE,
    CONTRADICTION
  }

  public record ConflictResult(ConflictType type, Memory conflicting, double similarityScore) {}

  public ConflictResult check(Memory candidate, List<Memory> existingMemories) {
    if (candidate == null || existingMemories == null || existingMemories.isEmpty()) {
      return new ConflictResult(ConflictType.NONE, null, 0.0d);
    }
    Memory best = null;
    double bestScore = 0.0d;
    for (Memory memory : existingMemories) {
      double score = computeSimilarity(candidate.content(), memory.content());
      if (score > bestScore) {
        bestScore = score;
        best = memory;
      }
    }
    if (bestScore >= 0.95d) {
      return new ConflictResult(ConflictType.DUPLICATE, best, bestScore);
    }
    if (bestScore >= 0.8d) {
      return new ConflictResult(ConflictType.CONTRADICTION, best, bestScore);
    }
    return new ConflictResult(ConflictType.NONE, best, bestScore);
  }

  double computeSimilarity(String left, String right) {
    Set<String> a = terms(left);
    Set<String> b = terms(right);
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0d;
    }
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    if (union.isEmpty()) {
      return 0.0d;
    }
    return (double) intersection.size() / (double) union.size();
  }

  private Set<String> terms(String content) {
    if (content == null || content.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(content.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
        .filter(token -> !token.isBlank())
        .collect(Collectors.toSet());
  }
}
