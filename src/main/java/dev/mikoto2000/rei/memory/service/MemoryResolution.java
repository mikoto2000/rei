package dev.mikoto2000.rei.memory.service;
import java.util.List;
public record MemoryResolution(MemoryAction action, List<String> targetIds) {
  public MemoryResolution { java.util.Objects.requireNonNull(action); targetIds=List.copyOf(targetIds); }
}
