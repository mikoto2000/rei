package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Evidence-based wording; the introduction qualifies all inferred activities. */
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
      boolean strong=strongEvidence(s);
      boolean dense=s.unobservedSeconds()<=Duration.between(s.startedAt(),s.endedAt()).getSeconds()*.1;
      if(unknown) out.append("観測できた範囲でも主活動は判定できません。");
      else {
        if(!strong || !dense || s.continuity()==TrendSummarySegment.Continuity.INTERMITTENT) out.append("観測できた範囲では、");
        out.append(description(s));
        if(!strong) out.append(s.continuity()==TrendSummarySegment.Continuity.MIXED?"関連の画面が混在していました。":"関連の画面が断続的に表示されていました。");
        else if(s.continuity()==TrendSummarySegment.Continuity.MIXED) out.append("が混在。");
        else if(dense && s.continuity()==TrendSummarySegment.Continuity.CONTINUOUS) out.append("が中心。");
        else out.append("が断続的に見られました。");
      }
      if(!s.labels().isEmpty()) out.append(String.join("、",s.labels())).append(dense?"などが表示されていました。":"などが断続的に表示されていました。");
      if(!unknown && s.unknownSeconds()>0) out.append("主活動を判定できない時間帯もあります。");
      out.append('\n');
    }
    return out.toString();
  }
  private static boolean strongEvidence(TrendSummarySegment s) {
    if(s.knownSeconds()<s.observedSeconds()*.7) return false;
    double confidence=0;long seconds=0;
    var policy=new ActivityRolePolicy(0);
    var records=s.evidence().stream().sorted(Comparator.comparing(ActivityRecord::capturedAt)).toList();
    for(int i=0;i<records.size();i++) {
      var r=records.get(i);var roles=policy.classify(r);
      Instant start=r.capturedAt().isBefore(s.startedAt())?s.startedAt():r.capturedAt();
      Instant end=r.capturedAt().plusSeconds(r.durationEstimate());
      if(end.isAfter(s.endedAt())) end=s.endedAt();
      if(i+1<records.size() && end.isAfter(records.get(i+1).capturedAt())) end=records.get(i+1).capturedAt();
      long weight=Math.max(0,Duration.between(start,end).getSeconds());
      confidence+=roles.confidence()*weight;seconds+=weight;
    }
    return seconds>0 && confidence/seconds>=.7;
  }
  private static String description(TrendSummarySegment s) {
    var parts=new ArrayList<String>();
    var work=List.of("development","research","documentation").stream().filter(s.categories()::contains).map(TrendSummaryFormatter::categoryLabel).toList();
    if(!work.isEmpty()) parts.add((s.projects().size()==1?s.projects().getFirst()+"関連の":"")+String.join("・",work));
    var leisure=List.of("social","media","shopping").stream().filter(s.categories()::contains).map(TrendSummaryFormatter::categoryLabel).toList();
    if(!leisure.isEmpty()) parts.add(String.join("・",leisure));
    for(var c:List.of("gaming","communication","navigation","monitoring")) if(s.categories().contains(c)) parts.add(categoryLabel(c));
    return parts.isEmpty()?"複数の活動":String.join("と",parts.stream().limit(3).toList());
  }
  private static String categoryLabel(String category) {
    return switch(category) {
      case "development" -> "開発・確認";case "research" -> "調査";case "documentation" -> "文書作業";
      case "social" -> "SNS閲覧";case "media" -> "動画・音楽視聴";case "shopping" -> "ショッピング関連の閲覧";
      case "communication" -> "コミュニケーション";case "navigation" -> "予定・経路確認";
      case "gaming" -> "ゲーム関連の表示";
      case "monitoring" -> "監視";default -> "";
    };
  }
}
