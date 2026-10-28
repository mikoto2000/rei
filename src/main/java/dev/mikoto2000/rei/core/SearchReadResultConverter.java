package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.tooldelivery.ReadingJson;
import java.util.List;

/** Opt-in wire projection; internal source records and legacy delivery remain lossless. */
public final class SearchReadResultConverter
    implements org.springframework.ai.tool.execution.ToolCallResultConverter {
  @Override
  public String convert(Object result, java.lang.reflect.Type returnType) {
    try {
      var tree = ReadingJson.JSON.valueToTree(result);
      if (result instanceof List<?> files) {
        for (int index = 0; index < files.size(); index++) {
          if (!(files.get(index) instanceof Tools.SearchAndReadResult file) || !file.compact())
            continue;
          var matches = tree.get(index).path("matches");
          for (int m = 0; m < file.matches().size(); m++) {
            var match = file.matches().get(m);
            for (int sectionIndex = 0; sectionIndex < file.sections().size(); sectionIndex++) {
              var section = file.sections().get(sectionIndex);
              int offset = match.line() - section.startLine();
              if (offset >= 0
                  && offset < section.content().size()
                  && section.content().get(offset).equals(match.content())) {
                ((ObjectNode) matches.get(m)).remove("content");
                ((ObjectNode) matches.get(m)).put("sectionIndex", sectionIndex);
                break;
              }
            }
          }
        }
      }
      String wire = ReadingJson.JSON.writeValueAsString(tree);
      ReadingMetrics.add(
          "wireBytes", wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      ReadingMetrics.add(
          "wireEstimatedTokens",
          dev.mikoto2000.rei.tooldelivery.ReadingRuntime.estimateTokens(wire));
      return wire;
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Cannot encode search results", invalid);
    }
  }
}
