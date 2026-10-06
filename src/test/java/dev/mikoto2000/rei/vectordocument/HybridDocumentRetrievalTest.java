package dev.mikoto2000.rei.vectordocument;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import dev.mikoto2000.rei.vectorstore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridDocumentRetrievalTest {
  @Test void bm25OptInFlowsThroughDocumentGroupingAndReranking() {
    var store=mock(VectorStore.class,withSettings().extraInterfaces(RetrievalCandidates.class));
    var backend=(RetrievalCandidates)store;
    when(backend.bm25Search(any())).thenReturn(List.of(doc("bm25")));
    when(backend.denseSearch(any())).thenReturn(List.of(doc("dense")));
    var service=new VectorDocumentService(store,mock(VectorDocumentRepository.class),new VectorDocumentProperties(512,0));
    service.setRetrieval(new HybridRetrievalProperties(true,40,60,true));
    service.setRerankService(new CandidateReranker(){public <T> List<T> rerank(String q,List<T> candidates,java.util.function.Function<T,String> text){assertEquals(2,candidates.size());return candidates;}});
    assertEquals(2,service.search("spring",2,null,null).size());
    verify(backend).bm25Search(any());
    verify(backend,never()).lexicalSearch(any());
  }
  Document doc(String id){return Document.builder().id(id+"#0").text("body "+id).metadata(Map.of("docId",id,"source",id+".txt","chunkIndex",0)).score(.9).build();}
  @Test void optInUsesBothStreamsAndExistingRerankerBeforeFinalLimit() {
    var store=mock(VectorStore.class,withSettings().extraInterfaces(RetrievalCandidates.class));
    var backend=(RetrievalCandidates)store;
    when(backend.denseSearch(any())).thenReturn(List.of(doc("a"),doc("b")));
    when(backend.lexicalSearch(any())).thenReturn(List.of(doc("a")));
    var service=new VectorDocumentService(store,mock(VectorDocumentRepository.class),new VectorDocumentProperties(512,0));
    service.setRetrieval(new HybridRetrievalProperties(true,40,60));
    var seen=new java.util.concurrent.atomic.AtomicInteger();
    service.setRerankService(new CandidateReranker(){public <T> List<T> rerank(String q,List<T> candidates,java.util.function.Function<T,String> text){seen.set(candidates.size());assertEquals("body a",text.apply(candidates.getFirst()));return candidates.reversed();}});
    var results=service.search("question",1,null,null);
    assertEquals(2,seen.get());assertEquals("b",results.getFirst().docId());assertEquals(1,results.size());
    verify(store,never()).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
    assertThrows(IllegalArgumentException.class,()->service.search("question",65,null,null));
  }
  @Test void defaultRemainsLegacyAndUnsupportedOptInBackendFailsClearly() {
    var store=mock(VectorStore.class);when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(doc("legacy")));
    var service=new VectorDocumentService(store,mock(VectorDocumentRepository.class),new VectorDocumentProperties(512,0));
    assertEquals("legacy",service.search("question",1,null,null).getFirst().docId());
    service.setRetrieval(new HybridRetrievalProperties(true,40,60));
    assertThrows(IllegalStateException.class,()->service.search("question",1,null,null));
  }
}
