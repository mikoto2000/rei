package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Trend wording always qualifies the time range; no inference about actions during missing time. */
public record TrendSummaryFormatter(ZoneId zone) {
  public String format(List<TrendSummarySegment> segments) {
    if(segments.isEmpty()) return "この期間の Activity 記録はありません。";
    var out=new StringBuilder("画面の観測に基づく振り返りです。表示内容からの推定を含み、実際の操作・集中を断定するものではありません。\n");
    var time=DateTimeFormatter.ofPattern("HH:mm").withZone(zone);String section="";
    for(var s:segments) {
      int hour=s.startedAt().atZone(zone).getHour();String next=hour<6?"深夜":hour<12?"午前":hour<18?"午後":"夜";
      if(!next.equals(section)) {out.append('\n').append(next).append(":\n");section=next;}
      out.append("- ").append(time.format(s.startedAt())).append("–").append(time.format(s.endedAt())).append(" ");
      boolean unknown=s.knownSeconds()==0;
      if(unknown) out.append("観測できた範囲でも主活動は判定できません。");
      else {
        out.append("観測できた範囲では、");
        out.append(description(s));
        out.append(s.continuity()==TrendSummarySegment.Continuity.CONTINUOUS?"が見られました。":"が断続的に見られました。");
      }
      if(!s.labels().isEmpty()) out.append(String.join("・",s.labels())).append("などが表示されていました。");
      if(!unknown && s.unknownSeconds()>0) out.append("主活動を判定できない時間帯もあります。");
      out.append('\n');
    }
    return out.toString();
  }
  private static String description(TrendSummarySegment s) {
    var parts=new ArrayList<String>();
    var work=List.of("development","research","documentation").stream().filter(s.categories()::contains).map(TrendSummaryFormatter::categoryLabel).toList();
    if(!work.isEmpty()) parts.add((s.projects().size()==1?ActivityDisplayLabels.label(s.projects().getFirst())+"の":"")+String.join("・",work));
    var leisure=List.of("social","media","shopping").stream().filter(s.categories()::contains).map(TrendSummaryFormatter::categoryLabel).toList();
    if(!leisure.isEmpty()) parts.add(String.join("・",leisure));
    for(var c:List.of("communication","navigation","monitoring")) if(s.categories().contains(c)) parts.add(categoryLabel(c));
    return parts.isEmpty()?"複数の画面":String.join("や",parts.stream().limit(3).toList())+"に関する画面";
  }
  private static String categoryLabel(String category) {
    return switch(category) {
      case "development" -> "開発・確認";case "research" -> "調査";case "documentation" -> "文書作業";
      case "social" -> "SNS";case "media" -> "動画・音楽";case "shopping" -> "ショッピング";
      case "communication" -> "コミュニケーション";case "navigation" -> "予定・経路確認";
      case "monitoring" -> "監視";default -> "";
    };
  }
}
