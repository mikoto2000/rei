package dev.mikoto2000.rei.vectorstore;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridRetrievalTest {
  Document doc(String id,double score){return Document.builder().id(id).text(id).metadata(Map.of("docId",id,"source","safe","chunkIndex",0)).score(score).build();}
  @Test void rankFusionKeepsCandidatesFromEitherRetrieverAndRewardsAgreement() {
    var fusion=new ReciprocalRankFusion(60);
    var results=fusion.fuse(List.of(List.of("dense-only","both"),List.of("lexical-only","both")),s->s,5);
    assertEquals(List.of("both","dense-only","lexical-only"),results.stream().map(ReciprocalRankFusion.Ranked::item).toList());
    assertEquals(61.0/62,results.getFirst().score(),.000001);
  }
  @Test void duplicatesDoNotAddWeightAndTiesAreStable() {
    var fusion=new ReciprocalRankFusion(60);
    var results=fusion.fuse(List.of(List.of("z","z"),List.of("a")),s->s,2);
    assertEquals(List.of("a","z"),results.stream().map(ReciprocalRankFusion.Ranked::item).toList());
    assertEquals(.5,results.getFirst().score());
    assertThrows(IllegalArgumentException.class,()->fusion.fuse(List.of(Collections.nCopies(257,"z")),s->s,2));
  }
  @Test void pipelinePreservesFilterAndThresholdAndBoundsBothStreams() {
    var backend=mock(RetrievalCandidates.class);
    when(backend.denseSearch(any())).thenReturn(List.of(doc("dense",.99),doc("both",.9)));
    when(backend.lexicalSearch(any())).thenReturn(List.of(doc("lexical",1),doc("both",.5)));
    var request=SearchRequest.builder().query("question").topK(3).similarityThreshold(.4).filterExpression(new FilterExpressionBuilder().eq("source","safe").build()).build();
    var results=new HybridRetriever(backend,new HybridRetrievalProperties(true,40,60)).search(request);
    assertEquals("both",results.getFirst().getId());assertEquals(2,results.getFirst().getMetadata().get("denseRank"));
    assertEquals(2,results.getFirst().getMetadata().get("lexicalRank"));assertEquals("rrf",results.getFirst().getMetadata().get("retrievalMode"));
    var requests=org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
    verify(backend).denseSearch(requests.capture());verify(backend).lexicalSearch(requests.capture());
    for(var captured:requests.getAllValues()){assertEquals(40,captured.getTopK());assertEquals(request.getFilterExpression(),captured.getFilterExpression());assertEquals(.4,captured.getSimilarityThreshold());}
  }
  @Test void blankQueryMakesNoCallsAndFailuresAndCancellationAreNotHidden() {
    var backend=mock(RetrievalCandidates.class);var search=new HybridRetriever(backend,new HybridRetrievalProperties(true,40,60));
    assertTrue(search.search(SearchRequest.builder().query(" ").topK(3).build()).isEmpty());verifyNoInteractions(backend);
    when(backend.lexicalSearch(any())).thenThrow(new UnsupportedOperationException("filter"));
    assertThrows(UnsupportedOperationException.class,()->search.search(SearchRequest.builder().query("query").build()));verify(backend,never()).denseSearch(any());
    assertThrows(IllegalArgumentException.class,()->new HybridRetrievalProperties(true,257,60));
    try {Thread.currentThread().interrupt();assertThrows(java.util.concurrent.CancellationException.class,()->search.search(SearchRequest.builder().query("query").build()));}
    finally {Thread.interrupted();}
  }
}
