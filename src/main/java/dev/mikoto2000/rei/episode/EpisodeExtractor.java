package dev.mikoto2000.rei.episode;

import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import java.util.List;

/** 経験の記録に必要な情報を入力から取り出す。 */
public interface EpisodeExtractor {
  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  List<Episode> extract(
      String session,
      String project,
      List<Turn> turns,
      List<Episode> existing,
      ModelCallBudget budget);
}
