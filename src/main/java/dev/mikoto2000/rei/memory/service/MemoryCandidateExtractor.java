package dev.mikoto2000.rei.memory.service;
import java.util.List;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
@FunctionalInterface
public interface MemoryCandidateExtractor { List<MemoryCandidate> extract(List<Turn> turns); }
