package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class VectorDocumentTools {

  private final VectorDocumentService vectorDocumentService;

  @Tool(name = "vectorDocumentAdd", description = "ファイル群をベクトルストアに追加または再登録します。引数はファイルパスの一覧です。")
  List<VectorDocumentEntry> vectorDocumentAdd(List<String> sources) throws IOException {
    IO.println(String.format("ベクトル文書 %s を追加または再登録するよ", sources));
    return vectorDocumentService.add(sources);
  }

  @Tool(name = "vectorDocumentSearch", description = "ベクトルストアに登録済みの文書を類似検索します。query は必須です。topK, threshold, source は必要な場合だけ指定できます。")
  List<VectorDocumentSearchResult> vectorDocumentSearch(String query, Integer topK, Double threshold, String source)
      throws IOException {
    IO.println(String.format("ベクトル文書を検索するよ。query=%s、topK=%s、threshold=%s、source=%s",
        query, topK, threshold, source));
    return vectorDocumentService.search(query, topK, threshold, source);
  }

  @Tool(name = "vectorDocumentList", description = "ベクトルストアに登録済みの文書一覧を返します。")
  List<VectorDocumentEntry> vectorDocumentList() throws IOException {
    IO.println("ベクトル文書一覧を取得するよ");
    return vectorDocumentService.list();
  }

  @Tool(name = "vectorDocumentDeleteByDocId", description = "docId を指定してベクトルストアから文書を削除します。削除できた場合は true を返します。")
  boolean vectorDocumentDeleteByDocId(String docId) throws IOException {
    IO.println(String.format("docId=%s のベクトル文書を削除するよ", docId));
    return vectorDocumentService.deleteByDocId(docId);
  }

  @Tool(name = "vectorDocumentDeleteBySource", description = "source に一致する文書群をベクトルストアから削除します。返り値は削除した文書数です。")
  int vectorDocumentDeleteBySource(String source) throws IOException {
    IO.println(String.format("source=%s のベクトル文書を削除するよ", source));
    return vectorDocumentService.deleteBySource(source);
  }
}
