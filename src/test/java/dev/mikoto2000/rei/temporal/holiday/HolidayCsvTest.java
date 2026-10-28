package dev.mikoto2000.rei.temporal.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HolidayCsvTest {
  static final String HEADER = "国民の祝日・休日月日,国民の祝日・休日名称";
  static final Charset MS932 = Charset.forName("MS932");

  @Test
  void readsOfficialEncodingDatesAndLineEndings() throws Exception {
    var dates = HolidayCsv.parse((HEADER + "\r\n2026/11/23,勤労感謝の日\r\n").getBytes(MS932));
    assertEquals("勤労感謝の日", dates.get(LocalDate.of(2026, 11, 23)));
    assertThrows(UnsupportedOperationException.class, dates::clear);
  }

  @Test
  void acceptsUtf8OnlyWithBom() throws Exception {
    var data = ("\ufeff" + HEADER + "\n2026/1/1,元日\n").getBytes(StandardCharsets.UTF_8);
    assertEquals(1, HolidayCsv.parse(data).size());
    assertThrows(
        IOException.class,
        () -> HolidayCsv.parse((HEADER + "\n2026/1/1,元日").getBytes(StandardCharsets.UTF_8)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "国民の祝日・休日月日,国民の祝日・休日名称",
        "date,name\n2026/1/1,元日",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/2/30,休日",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026-01-01,元日",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/1/1,元日\n2026/1/1,重複",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/1/1,元日,余分",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/1/1",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/1/1,",
        "国民の祝日・休日月日,国民の祝日・休日名称\n\n2026/1/1,元日",
        "国民の祝日・休日月日,国民の祝日・休日名称\n2026/1/1,\"元日"
      })
  void rejectsMalformedOrEmptyData(String csv) {
    assertThrows(IOException.class, () -> HolidayCsv.parse(csv.getBytes(MS932)));
  }
}
