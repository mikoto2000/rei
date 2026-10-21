package dev.mikoto2000.rei.topic;

import java.util.List;

/** 話題候補の内容を生成する。 */
public interface TopicCandidateGenerator {
  /** 指定された条件に従って、依頼された内容を生成する。 */
  List<TopicCandidate> generate(TopicGenerationContext context);
}
