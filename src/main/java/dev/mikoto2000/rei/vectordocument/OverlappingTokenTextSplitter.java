package dev.mikoto2000.rei.vectordocument;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.transformer.splitter.TextSplitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

class OverlappingTokenTextSplitter extends TextSplitter {

  private final Encoding encoding;
  private final int chunkSize;
  private final int chunkOverlap;

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
