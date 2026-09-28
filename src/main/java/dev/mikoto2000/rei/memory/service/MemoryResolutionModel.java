package dev.mikoto2000.rei.memory.service;
import java.util.List;
import dev.mikoto2000.rei.memory.model.*;
@FunctionalInterface
public interface MemoryResolutionModel { MemoryResolution resolve(MemoryCandidate candidate, List<LongTermMemory> existing); }
