package dev.mikoto2000.rei.episode;
import java.time.*;
public record EpisodeTimeRange(Instant from,Instant until,boolean exclusiveEnd) {
  public static EpisodeTimeRange of(String since,String until) {
    Instant start=since==null||since.isBlank()?null:parse(since,false);
    boolean dateEnd=until!=null&&until.matches("\\d{4}-\\d{2}-\\d{2}");
    Instant end=until==null||until.isBlank()?null:parse(until,dateEnd);
    if(start!=null&&end!=null&&end.isBefore(start))throw new IllegalArgumentException("Inverted episode time range");
    return new EpisodeTimeRange(start,end,dateEnd);
  }
  private static Instant parse(String value,boolean nextDay) {
    if(value.matches("\\d{4}-\\d{2}-\\d{2}"))return LocalDate.parse(value).plusDays(nextDay?1:0).atStartOfDay(ZoneOffset.UTC).toInstant();
    return OffsetDateTime.parse(value).toInstant();
  }
  public boolean includes(Instant time){return (from==null||!time.isBefore(from))&&(until==null||(exclusiveEnd?time.isBefore(until):!time.isAfter(until)));}
  public static String sortable(Instant value){return new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(value);}
}
