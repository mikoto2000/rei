package dev.mikoto2000.rei.temporal.holiday;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 内閣府の二列CSVを検証し、完全に解析できた場合だけ不変のデータを返す。 */
final class HolidayCsv {
  private static final String HEADER = "国民の祝日・休日月日,国民の祝日・休日名称";
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern ROW = Pattern.compile("(?:\"([0-9]{4}/[0-9]{1,2}/[0-9]{1,2})\"|([0-9]{4}/[0-9]{1,2}/[0-9]{1,2})),(?:\"([^\"\\r\\n]+)\"|([^,\"\\r\\n]+))");

  private HolidayCsv() {}

  static NavigableMap<LocalDate, String> parse(byte[] bytes) throws IOException {
    boolean bom = bytes.length >= 3 && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    var charset = bom ? StandardCharsets.UTF_8 : Charset.forName("MS932");
    String text = charset.newDecoder().decode(ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0))).toString();
    var dates = new TreeMap<LocalDate, String>();
    try (var reader = new BufferedReader(new StringReader(text))) {
      if (!HEADER.equals(reader.readLine())) throw new IOException("Invalid Cabinet Office CSV header");
      String line;
      int number = 1;
      while ((line = reader.readLine()) != null) {
        number++;
        var match = ROW.matcher(line);
        if (!match.matches()) throw new IOException("Invalid CSV columns at line " + number);
        try {
          var date = LocalDate.parse(match.group(1) == null ? match.group(2) : match.group(1), DATE);
          String name = match.group(3) == null ? match.group(4) : match.group(3);
          if (name.isBlank() || dates.putIfAbsent(date, name) != null)
            throw new IOException("Blank name or duplicate date at line " + number);
        } catch (DateTimeParseException e) {
          throw new IOException("Invalid CSV date at line " + number, e);
        }
      }
    }
    if (dates.isEmpty()) throw new IOException("CSV contains no holidays");
    return Collections.unmodifiableNavigableMap(dates);
  }
}
