package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded deterministic wording. Vision prose is evidence, never copied into the user summary. */
public record ActivitySummaryFormatter(ZoneId zone) {
  public String format(List<SummarySegment> segments) {
    if(segments.isEmpty()) return "この期間の Activity 記録はありません。";
    var out=new StringBuilder("画面の観測に基づく振り返りです。\n表示内容からの推定を含み、実際の操作・集中を断定するものではありません。\n");
    var time=DateTimeFormatter.ofPattern("HH:mm").withZone(zone);String section="";
    for(var segment:segments) {
      int hour=segment.startedAt().atZone(zone).getHour();
      String next=hour<6?"深夜":hour<12?"午前":hour<18?"午後":"夜";
      if(!next.equals(section)) {out.append('\n').append(next).append(":\n");section=next;}
      out.append("- ").append(time.format(segment.startedAt())).append("–").append(time.format(segment.endedAt())).append(' ');
      var primary=segment.roles().primary();
      if(primary==null) out.append("主活動は判定できません。");
      else {
        String project=clean(primary.projectCandidate(),60);
        if(!project.isBlank()) out.append(project).append("関連の");
        out.append(label(primary.type())).append("が中心と推定。");
      }
      var labels=segment.roles().secondary().stream().map(a->a.service().isBlank()?label(a.type()):clean(a.service(),40)).distinct().toList();
      if(!labels.isEmpty()) {
        out.append(String.join("・",labels.stream().limit(3).toList()));
        if(labels.size()>3) out.append("など");
        out.append("も並行して表示。");
      }
      if(segment.unobservedSeconds()>0) out.append("（未観測 ").append(segment.unobservedSeconds()).append("秒を含む）");
      out.append('\n');
    }
    return out.toString();
  }
  private static String clean(String text,int max) {
    String clean=text.replaceAll("[\\p{Cntrl}\\s]+"," ").strip();
    return clean.substring(0,Math.min(max,clean.length()));
  }
  private static String label(String category) {
    return switch(ActivityRolePolicy.normalize(category)) {
      case "coding","development","debugging","programming","devops" -> "開発・確認作業";
      case "research","browsing","web" -> "Web調査・閲覧";
      case "social","sns" -> "SNS閲覧";
      case "media","video","music" -> "動画・音楽関連の表示";
      case "communication" -> "コミュニケーション関連の作業";
      case "documentation" -> "文書作業";
      case "shopping" -> "ショッピング関連の閲覧";
      case "gaming" -> "ゲーム関連の表示";
      case "monitoring","dashboard","system_monitor" -> "監視画面の確認";
      default -> "画面上の活動";
    };
  }
}
