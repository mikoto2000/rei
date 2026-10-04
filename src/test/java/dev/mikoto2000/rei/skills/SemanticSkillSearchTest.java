package dev.mikoto2000.rei.skills;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticSkillSearchTest {
  AgentSkill skill(String name,String description,boolean enabled){return new AgentSkill(name,description,List.of(),enabled,Path.of(name),Path.of(name,"SKILL.md"),"private instructions must never be embedded");}
  SemanticSkillProperties config(){return new SemanticSkillProperties(true,64,.55,30);}
  @Test void paraphraseFindsSkillAndDisabledSkillsAndInstructionsAreExcluded() {
    var texts=new ArrayList<String>();
    SkillMetadataEmbedding model=input->{texts.addAll(input);return input.stream().map(s->s.contains("writer") || s.contains("compose")?new float[]{1,0}:new float[]{0,1}).toList();};
    var selector=new SkillCandidateSelector();selector.semanticSearch(new SemanticSkillSearch(config(),()->model,()->null,System::nanoTime));
    var result=selector.selectCandidates("compose a narrative",List.of(skill("writer","technical articles",true),skill("disabled","compose",false),skill("shell","commands",true)),5);
    assertEquals(List.of("writer"),result.stream().map(c->c.skill().name()).toList());
    assertTrue(result.getFirst().matchedFields().contains("semantic"));assertTrue(texts.stream().noneMatch(t->t.contains("private") || t.contains("disabled")));
  }
  @Test void metadataCacheRefreshesAndAlwaysReturnsCurrentSkillObject() {
    var calls=new AtomicInteger();SkillMetadataEmbedding model=input->{calls.incrementAndGet();return input.stream().map(s->new float[]{1,0}).toList();};
    var selector=new SkillCandidateSelector();selector.semanticSearch(new SemanticSkillSearch(config(),()->model,()->null,System::nanoTime));
    var original=skill("writer","articles",true);selector.selectCandidates("compose",List.of(original),5);assertEquals(2,calls.get());
    var replacement=new AgentSkill(original.name(),original.description(),original.keywords(),true,original.directory(),original.skillFile(),"updated instructions");
    assertSame(replacement,selector.selectCandidates("compose",List.of(replacement),5).getFirst().skill());assertEquals(3,calls.get());
    selector.selectCandidates("compose",List.of(skill("writer","revised description",true)),5);assertEquals(5,calls.get());
  }
  @Test void disabledOverLimitAndInvalidVectorsFallBackWithoutRepeatedModelCalls() {
    var calls=new AtomicInteger();SkillMetadataEmbedding failed=input->{calls.incrementAndGet();throw new IllegalStateException("private failure");};
    var nanos=new AtomicLong();var selector=new SkillCandidateSelector();
    selector.semanticSearch(new SemanticSkillSearch(new SemanticSkillProperties(false,64,.55,30),()->failed,()->null,nanos::get));
    assertFalse(selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5).isEmpty());assertEquals(0,calls.get());
    selector.semanticSearch(new SemanticSkillSearch(config(),()->failed,()->null,nanos::get));
    selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5);selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5);assertEquals(1,calls.get());
    nanos.set(31_000_000_000L);selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5);assertEquals(2,calls.get());
    selector.semanticSearch(new SemanticSkillSearch(new SemanticSkillProperties(true,1,.55,30),()->failed,()->null,nanos::get));
    selector.selectCandidates("writer",List.of(skill("writer","articles",true),skill("shell","commands",true)),5);assertEquals(2,calls.get());
  }
  @Test void cancellationIsPropagatedRatherThanConvertedToKeywordFallback() {
    SkillMetadataEmbedding model=input->{throw new java.util.concurrent.CancellationException();};
    var selector=new SkillCandidateSelector();selector.semanticSearch(new SemanticSkillSearch(config(),()->model,()->null,System::nanoTime));
    assertThrows(java.util.concurrent.CancellationException.class,()->selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5));
  }
  @Test void malformedVectorsPreserveKeywordCandidates() {
    for (var vector : List.of(new float[]{Float.NaN}, new float[0])) {
      var selector=new SkillCandidateSelector();
      selector.semanticSearch(new SemanticSkillSearch(config(),()->input->input.stream().map(s->vector).toList(),()->null,System::nanoTime));
      var result=selector.selectCandidates("writer",List.of(skill("writer","articles",true)),5);
      assertEquals("writer",result.getFirst().skill().name());
      assertFalse(result.getFirst().matchedFields().contains("rrf"));
    }
  }
  @Test void rerankingRunsBeforeLimitAndRejectsForeignIdentities() {
    SkillMetadataEmbedding model=input->input.stream().map(s->new float[]{1,0}).toList();
    var ranker=new dev.mikoto2000.rei.vectordocument.CandidateReranker() {
      public <T> List<T> rerank(String query,List<T> candidates,java.util.function.Function<T,String> text) {
        assertEquals(2,candidates.size());
        assertTrue(candidates.stream().map(text).noneMatch(s->s.contains("private instructions")));
        var reversed=new ArrayList<>(candidates);Collections.reverse(reversed);return reversed;
      }
    };
    var selector=new SkillCandidateSelector();
    selector.semanticSearch(new SemanticSkillSearch(config(),()->model,()->ranker,System::nanoTime));
    assertEquals("writer",selector.selectCandidates("compose",List.of(skill("shell","commands",true),skill("writer","articles",true)),1).getFirst().skill().name());
    var invalid=new dev.mikoto2000.rei.vectordocument.CandidateReranker() {
      public <T> List<T> rerank(String query,List<T> candidates,java.util.function.Function<T,String> text) { return List.of(); }
    };
    selector.semanticSearch(new SemanticSkillSearch(config(),()->model,()->invalid,System::nanoTime));
    assertEquals("shell",selector.selectCandidates("compose",List.of(skill("shell","commands",true),skill("writer","articles",true)),1).getFirst().skill().name());
  }
}
