package dev.mikoto2000.rei.paper;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class PaperExtractionService {
  private final PaperProperties config;
  private static final java.util.regex.Pattern HEADING =
      java.util.regex.Pattern.compile(
          "(?i)^(?:[0-9]+(?:\\.[0-9]+)*[. ]*\\s*)?(abstract|introduction|background|related"
              + " work|method|methodology|experiments|evaluation|results|discussion|limitations|future"
              + " work|conclusions?|references)\\s*$");

  public PaperExtractionService(PaperProperties config) {
    this.config = config;
  }

  public StructuredPaper extract(String title, byte[] pdf, PaperOperation op) {
    op.check();
    if (pdf.length > config.getMaxPdfBytes()) throw failure();
    try (var doc = Loader.loadPDF(pdf)) {
      if (doc.getNumberOfPages() > config.getMaxPages()) throw failure();
      List<PaperSection> sections = new ArrayList<>();
      List<String> references = new ArrayList<>();
      int total = 0;
      String heading = "UNKNOWN";
      for (int page = 1; page <= doc.getNumberOfPages(); page++) {
        op.check();
        final int remaining = config.getMaxExtractedChars() - total;
        var writer =
            new Writer() {
              private final StringBuilder value = new StringBuilder();

              public void write(char[] b, int o, int n) throws IOException {
                op.check();
                if (value.length() + n > remaining) throw new IOException("extraction limit");
                value.append(b, o, n);
              }

              public void flush() {}

              public void close() {}

              public String toString() {
                return value.toString();
              }
            };
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.writeText(doc, writer);
        String text = writer.toString();
        total += text.length();
        StringBuilder block = new StringBuilder();
        for (String line : text.split("\\R")) {
          op.check();
          var m = HEADING.matcher(line.strip());
          if (m.matches()) {
            add(sections, heading, block.toString(), page);
            block.setLength(0);
            heading = m.group(1);
          } else block.append(line).append('\n');
        }
        add(sections, heading, block.toString(), page);
      }
      for (var s : sections)
        if (s.heading().equalsIgnoreCase("references")) references.add(s.text());
      if (sections.isEmpty()) throw failure();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info(
              "paper extraction pages={} chars={} sections={}",
              doc.getNumberOfPages(),
              total,
              sections.size());
      return new StructuredPaper(
          title, StructuredPaper.Availability.FULL_TEXT, sections, references, List.of());
    } catch (PaperException | java.util.concurrent.CancellationException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new PaperException(PaperException.Code.PDF_PARSE_FAILED, "PDF の解析に失敗しました", e);
    }
  }

  private void add(List<PaperSection> sections, String heading, String text, int page) {
    if (text.isBlank()) return;
    for (String chunk : PaperTranslationService.chunks(text, config.getMaxSectionChars())) {
      if (sections.size() >= config.getMaxSections()) throw failure();
      sections.add(new PaperSection(heading, chunk, page, page));
    }
  }

  private PaperException failure() {
    return new PaperException(PaperException.Code.PDF_PARSE_FAILED, "PDF が空、破損、または解析上限を超えています");
  }
}
