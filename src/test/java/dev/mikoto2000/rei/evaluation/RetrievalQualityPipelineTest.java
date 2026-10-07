package dev.mikoto2000.rei.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import dev.mikoto2000.rei.vectorstore.*;
import dev.mikoto2000.rei.skills.*;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;

class RetrievalQualityPipelineTest {
  record Item(String id,String text) {}
  record Example(String domain,RetrievalQualityEvaluation.Case fixture,List<Item> corpus) {}
  record Examples(List<Example> examples) {}
  List<Example> fixtures()throws Exception{try(var input=getClass().getResourceAsStream("/evaluation/retrieval-quality.json")){assertNotNull(input);return new com.fasterxml.jackson.databind.ObjectMapper().readValue(input,Examples.class).examples();}}
  CandidateReranker ranker(){return new CandidateReranker(){public <T>List<T> rerank(String query,List<T> candidates,java.util.function.Function<T,String> text){assertTrue(candidates.stream().map(text).noneMatch(s->s.contains("PRIVATE_INSTRUCTIONS")));var result=new ArrayList<>(candidates);result.sort(Comparator.comparingInt(c->text.apply(c).contains("Technical narrative")||text.apply(c).contains("Durable receipts")?0:1));return result;}};}
  SkillRetrievalEvaluationAdapter skills(Example fixture,SkillMetadataEmbedding embedding,CandidateReranker reranker){var catalog=fixture.corpus().stream().map(item->new AgentSkill(item.id(),item.text(),List.of(),true,Path.of(item.id()),Path.of(item.id(),"SKILL.md"),"PRIVATE_INSTRUCTIONS")).toList();return new SkillRetrievalEvaluationAdapter(catalog,new SemanticSkillProperties(true,64,.55,30),embedding,reranker);}
  @Test void actualHybridFusionAndRerankAreComparedAgainstLabelledHardNegative()throws Exception{
    var example=fixtures().stream().filter(f->f.domain().equals("document")).findFirst().orElseThrow();var corpus=new HashMap<String,Document>();example.corpus().forEach(item->corpus.put(item.id(),Document.builder().id(item.id()).text(item.text()).build()));
    var backend=new RetrievalCandidates(){public List<Document> lexicalSearch(SearchRequest request){assertEquals(example.fixture().query(),request.getQuery());return List.of(corpus.get("artifact-ui"),corpus.get("artifact-recovery"));}public List<Document> denseSearch(SearchRequest request){return List.of(corpus.get("artifact-recovery"),corpus.get("neutral"));}};
    var result=new RetrievalQualityEvaluation().compare(List.of(example.fixture()),"fixture-vector-provider",new HybridRetrievalEvaluationAdapter(backend,new HybridRetrievalProperties(true,10,60),ranker()));
    assertEquals(0,result.aggregate().get("LEXICAL_BASE").mrr());assertEquals(1,result.aggregate().get("DENSE_BASE").mrr());assertEquals(1,result.aggregate().get("RRF_BASE").mrr());assertEquals(1,result.aggregate().get("LEXICAL_RERANK").mrr());assertEquals(6,result.runs().size());
    Files.createDirectories(Path.of("target/evaluation"));new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/evaluation/rag-quality.json").toFile(),result);
  }
  @Test void actualSkillKeywordDenseFusionAndRerankUseMetadataOnly()throws Exception{
    var example=fixtures().stream().filter(f->f.domain().equals("skill")).findFirst().orElseThrow();SkillMetadataEmbedding embedding=input->input.stream().map(text->!text.startsWith("name: ")||text.contains("technical narrative")?new float[]{1,0}:new float[]{0,1}).toList();
    var result=new RetrievalQualityEvaluation().compare(List.of(example.fixture()),"fixture-skill-embedding",skills(example,embedding,ranker()));assertEquals(1,result.aggregate().get("DENSE_BASE").recall());assertEquals(1,result.aggregate().get("RRF_RERANK").recall());assertTrue(result.runs().stream().filter(r->r.mode()==RetrievalQualityEvaluation.Mode.LEXICAL&&!r.reranked()).noneMatch(r->r.metrics().expectedTopKMatched()));
    Files.createDirectories(Path.of("target/evaluation"));new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/evaluation/skill-quality.json").toFile(),result);
  }
  @Test void unavailableProvidersForeignRerankAndFallbackCannotMasqueradeAsEvaluationSuccess()throws Exception{
    var example=fixtures().stream().filter(f->f.domain().equals("skill")).findFirst().orElseThrow();assertThrows(IllegalStateException.class,()->skills(example,null,ranker()).retrieve(example.fixture(),RetrievalQualityEvaluation.Mode.DENSE,false));assertThrows(IllegalStateException.class,()->skills(example,input->{throw new IllegalStateException("fixture failure");},ranker()).retrieve(example.fixture(),RetrievalQualityEvaluation.Mode.DENSE,false));
    var failedRanker=new CandidateReranker(){public <T>List<T> rerank(String q,List<T> candidates,java.util.function.Function<T,String> text){throw new IllegalStateException("ranker unavailable");}};
    assertThrows(IllegalStateException.class,()->skills(example,input->input.stream().map(text->new float[]{1,0}).toList(),failedRanker).retrieve(example.fixture(),RetrievalQualityEvaluation.Mode.RRF,true));
  }
}
