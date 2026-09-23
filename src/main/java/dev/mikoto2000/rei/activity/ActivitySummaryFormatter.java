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
      long wall=Duration.between(segment.startedAt(),segment.endedAt()).getSeconds();
      double missing=wall==0?0:(double)segment.unobservedSeconds()/wall;
      if(missing>.3) out.append("観測できた時間帯のみ: ");
      var primary=segment.roles().primary();
      if(primary==null) out.append("主活動は判定できません。");
      else {
        String project=clean(ActivityRolePolicy.normalize(primary.projectCandidate()),60);
        if(!project.isBlank()) out.append(project).append("関連の");
        boolean mixed=segment.primaryCategories().size()>1;
        String main=mixed && segment.theme().equals("web-browsing")?"Web閲覧":
            mixed && segment.theme().equals("work-development")?String.join("・",segment.primaryCategories().stream()
                .map(c->switch(c) {case "development" -> "開発";case "research" -> "調査";case "documentation" -> "文書作業";default -> label(c);}).toList()):label(primary.type());
        out.append(main).append("が中心と推定。");
      }
      var visible=primary==null?java.util.stream.Stream.concat(segment.roles().secondary().stream(),segment.roles().background().stream()):segment.roles().secondary().stream();
      var labels=visible.map(a->a.service().isBlank()?ActivityVocabulary.applicationLabel(a.application()):ActivityVocabulary.serviceLabel(a.service()))
          .filter(s->!s.isBlank()).map(s->clean(s,40)).distinct().toList();
      if(!labels.isEmpty()) {
        out.append(String.join("・",labels.stream().limit(3).toList()));
        if(labels.size()>3) out.append("など");
        out.append(primary==null?"が表示されていました。":"も並行して表示。");
      }
      if(missing>=.1 && missing<=.3) out.append("（一部未観測時間あり）");
      else if(missing>.3) out.append("（未観測の割合が高い期間）");
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
      case "navigation" -> "予定・経路の確認";
      case "idle" -> "待機状態の表示";
      case "other" -> "その他の活動";
      default -> "未分類の表示";
    };
  }
}
