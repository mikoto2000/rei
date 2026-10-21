package dev.mikoto2000.rei.paper;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/** 論文Extractionのユースケースを実現する。 */
@Service
public class PaperExtractionService {
  /** 設定。 */
  private final PaperProperties config;

  /** 見出し。 */
  private static final java.util.regex.Pattern HEADING =
      java.util.regex.Pattern.compile(
          "(?i)^(?:[0-9]+(?:\\.[0-9]+)*[. ]*\\s*)?(abstract|introduction|background|related"
              + " work|method|methodology|experiments|evaluation|results|discussion|limitations|future"
              + " work|conclusions?|references)\\s*$");

  /** 指定された値や利用先を使って、PaperExtractionServiceを初期化する。 */
  public PaperExtractionService(PaperProperties config) {
    this.config = config;
  }

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
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
              /** 値。 */
              private final StringBuilder value = new StringBuilder();

              /** 指定された内容を書き込み先へ反映する。 */
              public void write(char[] b, int o, int n) throws IOException {
                op.check();
                if (value.length() + n > remaining) throw new IOException("extraction limit");
                value.append(b, o, n);
              }

              /** 論文の調査と整理に関する保存の確定を処理する。 */
              public void flush() {}

              /** 論文の調査と整理で利用した資源や登録を解放し、利用を終了する。 */
              public void close() {}

              /** 論文の調査と整理の情報を、表示や診断で確認できる文字列表現にする。 */
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

  /** 新たな対象を登録し、後から利用できるようにする。 */
  private void add(List<PaperSection> sections, String heading, String text, int page) {
    if (text.isBlank()) return;
    for (String chunk : PaperTranslationService.chunks(text, config.getMaxSectionChars())) {
      if (sections.size() >= config.getMaxSections()) throw failure();
      sections.add(new PaperSection(heading, chunk, page, page));
    }
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  private PaperException failure() {
    return new PaperException(PaperException.Code.PDF_PARSE_FAILED, "PDF が空、破損、または解析上限を超えています");
  }
}
