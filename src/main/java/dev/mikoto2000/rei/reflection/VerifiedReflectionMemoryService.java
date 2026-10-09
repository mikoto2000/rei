package dev.mikoto2000.rei.reflection;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.MemoryRepository;

/** Explicit promotion of a re-observed predicate, never a model's lesson or arbitrary Reflection prose. */
@Service
public class VerifiedReflectionMemoryService {
  public record Promotion(LongTermMemory memory,MemoryRepository.ReflectionProof proof) {}
  private final GoalRepository goals; private final GoalReflectionRepository reflections; private final MemoryRepository memories;
  private final FileGoalVerifier verifier; private final GoalLoopService.Gateway owner; private final MemoryProperties properties; private final Clock clock;
  public VerifiedReflectionMemoryService(GoalRepository goals,GoalReflectionRepository reflections,MemoryRepository memories,FileGoalVerifier verifier,GoalLoopService.Gateway owner,MemoryProperties properties,Clock clock) {
    this.goals=goals;this.reflections=reflections;this.memories=memories;this.verifier=verifier;this.owner=owner;this.properties=properties;this.clock=clock;
  }
  /** Human-facing only. A repeat returns the original historical proof, including archived memories. */
  // Serialize this singleton's human promotions; SQLite reads can otherwise race a sibling writer.
  public synchronized Promotion promote(String project,String reflectionId) {
    if(!properties.enabled())throw new IllegalStateException("Memory is disabled");
    var reflection=reflections.get(project,reflectionId);var goal=goals.get(project,reflection.goalId());owner.validate(goal);
    if(!"COMPLETED".equals(reflection.goalStatus())||!"VERIFIED".equals(reflection.actual())||!"COMPLETED".equals(goal.status())
        ||!Set.of("file_digest_verified","criteria_verified","completion_gate_verified").contains(goal.reason())||!goal.reason().equals(reflection.actualReason())
        ||!goal.sessionId().equals(reflection.sessionId())||!Objects.equals(goal.currentRunId()==null?"":goal.currentRunId(),reflection.runId())||!reflections.matchesCriteria(reflection,goal))
      throw new IllegalArgumentException("Only matching completed and independently verified Goal reflections can be promoted");
    var previous=memories.verifiedReflectionProof(project,reflectionId);
    if(previous.isPresent())return new Promotion(memories.find(previous.get().memoryId()).orElseThrow(),previous.get());
    var verification=verifier.verify(goal);
    if(!verification.satisfied())throw new IllegalStateException("Independent verification failed: "+verification.reason());
    if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Independent verification cancelled");
    Instant verifiedAt=clock.instant();String criteria;
    try{criteria=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(goal.criteria());}catch(com.fasterxml.jackson.core.JsonProcessingException impossible){throw new IllegalStateException(impossible);}
    String digest;
    try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(criteria.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    String content="Historical local observation at "+verifiedAt+": saved criteria of Goal "+goal.id()+" matched during independent verification. Reflection "+reflection.id()+". Criteria: "+criteria+". This does not establish future validity or a general procedure.";
    // The source ID satisfies candidate validation but is not stored as a fabricated conversation turn.
    var candidate=new MemoryCandidate(MemoryType.PROJECT_STATE,MemoryScope.PROJECT,content,"Historical independent Goal criterion match: "+goal.id(),1,.7,List.of(reflection.id()),List.of("verified-goal-observation","historical-fact"));
    var memory=memories.insertVerifiedReflection(candidate,project,reflection.sessionId(),reflection.id(),goal.id(),digest,verifiedAt);
    return new Promotion(memory,memories.verifiedReflectionProof(project,reflectionId).orElseThrow());
  }
}
