package dev.mikoto2000.rei.vectordocument;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.transformer.splitter.TextSplitter;

/** 検索対象文書の管理に必要なOverlappingトークンテキストSplitterを扱う。 */
class OverlappingTokenTextSplitter extends TextSplitter {

  /** 文字コード。 */
  private final Encoding encoding;

  /** 文書の分割単位大きさ。 */
  private final int chunkSize;

  /** 文書の分割単位Overlap。 */
  private final int chunkOverlap;

  /** 指定された値や利用先を使って、OverlappingTokenTextSplitterを初期化する。 */
  OverlappingTokenTextSplitter(int chunkSize, int chunkOverlap) {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be greater than zero");
    }
    if (chunkOverlap < 0) {
      throw new IllegalArgumentException("chunkOverlap must be zero or greater");
    }
    if (chunkOverlap >= chunkSize) {
      throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
    }
    this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    this.chunkSize = chunkSize;
    this.chunkOverlap = chunkOverlap;
  }

  /** 検索対象文書の管理で用いる分割テキストを返す。 */
  @Override
  protected List<String> splitText(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }

    IntArrayList tokens = encoding.encode(text);
    if (tokens.isEmpty()) {
      return List.of();
    }

    List<String> chunks = new ArrayList<>();
    int step = Math.max(1, chunkSize - chunkOverlap);
    for (int start = 0; start < tokens.size(); start += step) {
      IntArrayList chunkTokens = new IntArrayList(Math.min(chunkSize, tokens.size() - start));
      for (int index = start; index < Math.min(tokens.size(), start + chunkSize); index++) {
        chunkTokens.add(tokens.get(index));
      }

      String chunk = encoding.decode(chunkTokens).trim();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }
      if (start + chunkSize >= tokens.size()) {
        break;
      }
    }
    return chunks;
  }
}
