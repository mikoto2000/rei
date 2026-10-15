package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PdfDeliveryTest {
  @TempDir Path dir;

  @Test
  void exactPageCursorRetainsPositionAndRejectsChangedVersion() throws Exception {
    var path = dir.resolve("source.pdf");
    try (var document = new PDDocument()) {
      for (var body :
          List.of("first page known fact", "middle page contradictory fact", "last page")) {
        var page = new PDPage();
        document.addPage(page);
        try (var stream = new PDPageContentStream(document, page)) {
          stream.beginText();
          stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          stream.newLineAtOffset(20, 500);
          stream.showText(body);
          stream.endText();
        }
      }
      document.save(path.toFile());
    }
    var files = new Tools();
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var first = files.readPdfPage("source.pdf", 1, 0, null, 512);
      var middle = files.readPdfPage("source.pdf", 2, 0, first.version(), 512);
      assertThat(middle.page()).isEqualTo(2);
      assertThat(middle.totalPages()).isEqualTo(3);
      assertThat(middle.content()).contains("middle page contradictory fact");
      assertThat(
              TokenEstimator.tokenizerAware().text(StorageDatabase.JSON.writeValueAsString(middle)))
          .isLessThanOrEqualTo(512);
      assertThatThrownBy(() -> files.readPdfPage("source.pdf", 2, 0, "changed", 512))
          .hasMessageContaining("STALE_VERSION");
      assertThatThrownBy(() -> files.readPdfPage("source.pdf", 2, -1, first.version(), 512))
          .hasMessageContaining("INVALID_CURSOR");
    }
  }
}
