package dev.mikoto2000.rei.topic;

import java.util.List;

public interface TopicCandidateGenerator {
  List<TopicCandidate> generate(TopicGenerationContext context);
}
