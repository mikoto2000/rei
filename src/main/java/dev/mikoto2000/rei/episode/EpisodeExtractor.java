package dev.mikoto2000.rei.episode;
import java.util.List;
import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
import dev.mikoto2000.rei.llm.ModelCallBudget;
public interface EpisodeExtractor {
  List<Episode> extract(String session,String project,List<Turn> turns,List<Episode> existing,ModelCallBudget budget);
}
