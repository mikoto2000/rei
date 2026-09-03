package dev.mikoto2000.rei.topic;

import java.util.List;

public interface SpeakDecisionPolicy {
  SpeakDecision decide(List<RankedTopicCandidate> candidates, SpeakDecisionContext context);
}
