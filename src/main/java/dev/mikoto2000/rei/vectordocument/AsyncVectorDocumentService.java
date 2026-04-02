package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AsyncVectorDocumentService {

  private final VectorDocumentService vectorDocumentService;

  @Async("embedTaskExecutor")
  public void addAsync(List<String> documents) {
    try {
      List<VectorDocumentEntry> entries = vectorDocumentService.add(documents);
      for (VectorDocumentEntry entry : entries) {
        System.out.println("追加完了: " + entry.docId() + " | " + entry.source() + " | chunks=" + entry.chunkCount());
      }
    } catch (IOException e) {
      System.out.println("追加失敗: " + String.join(", ", documents) + " | " + e.getMessage());
    } catch (RuntimeException e) {
      System.out.println("追加失敗: " + String.join(", ", documents) + " | " + e.getMessage());
    }
  }
}
