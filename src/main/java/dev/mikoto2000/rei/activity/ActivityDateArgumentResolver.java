package dev.mikoto2000.rei.activity;

import java.time.*;

/** Single-day arguments in the journal timezone; independent of command parsing. */
public record ActivityDateArgumentResolver(Clock clock) {
  public static final String USAGE="日付は today、yesterday、または YYYY-MM-DD 形式で指定してください。";

  public LocalDate resolve(String input) {
    var today=LocalDate.now(clock);
    LocalDate date;
    if(input==null || input.isEmpty() || input.equals("today")) date=today;
    else if(input.equals("yesterday")) date=today.minusDays(1);
    else {
      if(!input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException(USAGE);
      try {date=LocalDate.parse(input);}
      catch(DateTimeException e) {throw new DateTimeException(USAGE);}
    }
    if(date.isAfter(today)) throw new DateTimeException(date+" は未来の日付です。");
    return date;
  }
}
