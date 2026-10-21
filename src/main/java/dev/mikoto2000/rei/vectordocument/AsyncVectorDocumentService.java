package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 非同期ベクトル文書のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class AsyncVectorDocumentService {

  /** ベクトル文書サービス。 */
  private final VectorDocumentService vectorDocumentService;

  /** 活動中埋め込み表現。 */
  private final AtomicInteger activeEmbeddings = new AtomicInteger();

  /** 新たな対象を管理できるよう、非同期を追加する。 */
  @Async("embedTaskExecutor")
  public void addAsync(List<String> documents) {
    addAndReport(documents, System.out::println);
  }

  /**
   * 新たな対象を管理できるよう、およびレポートを追加する。
   *
   * <p>Runs on the caller's existing executor and preserves its Run/output ownership.
   */
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

  /** 検索対象文書の管理において、活動中埋め込み表現の有無を判定する。 */
  public boolean hasActiveEmbeddings() {
    return activeEmbeddings.get() > 0;
  }
}
