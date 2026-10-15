package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsyncVectorDocumentService {

  private final VectorDocumentService vectorDocumentService;
  private final AtomicInteger activeEmbeddings = new AtomicInteger();

  @Async("embedTaskExecutor")
  public void addAsync(List<String> documents) {
    addAndReport(documents, System.out::println);
  }

  /** Runs on the caller's existing executor and preserves its Run/output ownership. */
  public void addAndReport(List<String> documents, java.util.function.Consumer<String> output) {
    activeEmbeddings.incrementAndGet();
    try {
      List<VectorDocumentEntry> entries = vectorDocumentService.add(documents);
      for (VectorDocumentEntry entry : entries) {
        output.accept(
            "追加完了: " + entry.docId() + " | " + entry.source() + " | chunks=" + entry.chunkCount());
      }
    } catch (IOException e) {
      output.accept("追加失敗: " + String.join(", ", documents) + " | " + e.getMessage());
    } catch (RuntimeException e) {
      output.accept("追加失敗: " + String.join(", ", documents) + " | " + e.getMessage());
    } finally {
      activeEmbeddings.decrementAndGet();
    }
  }

  public boolean hasActiveEmbeddings() {
    return activeEmbeddings.get() > 0;
  }
}
