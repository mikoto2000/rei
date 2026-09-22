package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public record ActivityTimeline(ActivityStore store,Clock clock) {
  public List<ActivitySession> findByDate(LocalDate date) {
    return store.findBetween(date.atStartOfDay(clock.getZone()).toInstant(),date.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
  }
  public List<ActivitySession> findBetween(Instant start,Instant end) {
    if(!start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(31))>0)
      throw new IllegalArgumentException("Specify an increasing range of at most 31 days");
    return store.findBetween(start,end);
  }
  public List<ActivitySession> query(String day) {
    return findByDate(switch(day) {case "today","summary" -> LocalDate.now(clock);case "yesterday" -> LocalDate.now(clock).minusDays(1);default -> LocalDate.parse(day);});
  }
  /** Deterministic wording from sessions; no second inference or productivity evaluation. */
  public String summary(String day) {
    var sessions=query(day);
    if(sessions.isEmpty())return "この期間の Activity 記録はありません。";
    var output=new StringBuilder("画面の観測に基づく振り返り（実際の操作・集中を断定するものではありません）\n");
    String section=""; var format=DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone());
    for(var s:sessions) {
      int hour=s.startedAt().atZone(clock.getZone()).getHour();
      String next=hour<6?"深夜":hour<12?"午前":hour<18?"午後":"夜";
      if(!section.equals(next)) {output.append(next).append(":\n");section=next;}
      output.append("- ").append(format.format(s.startedAt())).append("–").append(format.format(s.endedAt())).append(" ")
          .append(s.inference().summary()).append("\n");
    }
    return output.toString();
  }
}
