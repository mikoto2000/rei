package dev.mikoto2000.rei.temporal.holiday;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 祝日をLLMが推測せず公式CSVから取得するための、読取専用ツール。 */
@Component
public class JapaneseHolidayTools {
  private static final Logger LOG = LoggerFactory.getLogger(JapaneseHolidayTools.class);
  private static final String DESCRIPTION = """
      日本の国民の祝日・休日を内閣府CSVで判定します。土日判定と国民の祝日は別です。
      年末年始休暇や個人的な休暇は含みません。対応年外・データ不足ではUNKNOWNになります。
      UNKNOWNの場合、祝日かどうかを推測して断定しないでください。
      UNCLASSIFIED_DAY_OFFは休日ですが法的種別が未確定です。
      dataCurrent=falseは最新ファイルの反映待ち・失敗を示します。
      """;
  private final JapaneseHolidayService service;

  public JapaneseHolidayTools(JapaneseHolidayService service) {
    this.service = service;
  }

  /** ファイルパス・例外内容を含めないLLM向けの失敗応答。 */
  public record ToolError(String error, String message) {}

  @Tool(name = "getHolidayInfo", description = DESCRIPTION + "dateは厳密なyyyy-MM-ddで指定してください。")
  public Object getHolidayInfo(String date) {
    return safely(() -> service.getHolidayInfo(parseDate(date)));
  }

  @Tool(name = "listHolidays", description = DESCRIPTION
      + "from/toはyyyy-MM-dd、両端を含め最大366日です。unknownPeriodsは完全性未確認の期間で、その中の既知の休日も一覧に含みます。")
  public Object listHolidays(String from, String to) {
    return safely(() -> service.listHolidays(parseDate(from), parseDate(to)));
  }

  private LocalDate parseDate(String date) {
    if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
      throw new IllegalArgumentException("Expected yyyy-MM-dd");
    try {
      return LocalDate.parse(date);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Invalid calendar date", e);
    }
  }

  private Object safely(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (IllegalArgumentException e) {
      return new ToolError("INVALID_ARGUMENT", "Use valid yyyy-MM-dd dates, from <= to, at most 366 days inclusive.");
    } catch (RuntimeException e) {
      LOG.warn("Holiday tool failed: {}", e.getClass().getSimpleName());
      return new ToolError("DATA_UNAVAILABLE", "Holiday data could not be queried. Do not infer holiday status.");
    }
  }
}
