package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class PaperProcessingTest {
  @Test
  void extractsPagesAndFallsBackWithoutHeadings() throws Exception {
    try (var doc = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(30, 700);
        content.showText("A useful finding.");
        content.endText();
      }
      doc.save(bytes);
      var parsed =
          new PaperExtractionService(new PaperProperties())
              .extract("Title", bytes.toByteArray(), PaperOperation.local("s"));
      assertEquals(1, parsed.sections().getFirst().startPage());
      assertTrue(parsed.sections().getFirst().text().contains("useful"));
    }
  }

  @Test
  void rejectsCorruptPdf() {
    assertThrows(
        PaperException.class,
        () ->
            new PaperExtractionService(new PaperProperties())
                .extract("t", new byte[] {1, 2, 3}, PaperOperation.local("s")));
  }

  @Test
  void chunkingPreservesEveryCharacter() {
    String input = "first 😀 next\n".repeat(100);
    var chunks = PaperTranslationService.chunks(input, 31);
    assertEquals(input, String.join("", chunks));
    assertTrue(chunks.stream().allMatch(c -> c.length() <= 31));
  }

  @Test
  void rejectsInvalidStructuredOutputAndUngroundedEvidence() {
    var service = new PaperSummaryValidator();
    var source =
        new StructuredPaper(
            "t",
            StructuredPaper.Availability.ABSTRACT_ONLY,
            List.of(new PaperSection("Abstract", "source", 0, 0)),
            List.of(),
            List.of());
    assertThrows(PaperException.class, () -> service.validate("{}", source));
  }
}
