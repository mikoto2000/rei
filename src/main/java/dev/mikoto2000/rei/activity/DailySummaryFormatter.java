package dev.mikoto2000.rei.activity;
public final class DailySummaryFormatter {
  public String format(DailySummaryAggregate a,DailySummary summary) {
    var out=new StringBuilder("Activity Summary — ").append(a.targetDate()).append("\n\n")
        .append("画面の観測に基づく振り返りです。表示内容からの推定を含み、実際の操作・集中を断定するものではありません。\n")
        .append("観測時間: ").append(duration(a.observedSeconds())).append(" / 未観測: ").append(duration(a.unobservedSeconds())).append("\n\n")
        .append("全体:\n").append(summary.overview()).append("\n");
    if(!summary.timeOfDay().isEmpty()) {
      out.append("\n時間帯別:\n");
      for(var key:DailySummaryAggregate.BUCKET_ORDER)if(summary.timeOfDay().containsKey(key))
        out.append(DailySummaryAggregate.BUCKET_LABELS.get(key)).append(":\n").append(summary.timeOfDay().get(key)).append("\n");
    }
    if(!summary.workThemes().isEmpty()){out.append("\n主な作業テーマ:\n");summary.workThemes().forEach(s->out.append("- ").append(s).append("\n"));}
    if(!summary.nonWorkActivities().isEmpty()){out.append("\n主な非作業活動（娯楽判定）:\n");summary.nonWorkActivities().forEach(s->out.append("- ").append(s).append("\n"));}
    out.append("\n傾向:\n").append(summary.trend()).append("\n");
    if(a.unknownRatio()>=.1)out.append("一部の時間帯は観測情報だけでは活動内容を十分に判定できませんでした。\n");
    if(a.entertainmentSeconds().getOrDefault(EntertainmentDisposition.UNCERTAIN,0L)>0)
      out.append("娯楽かどうか判定が確定していない観測は、娯楽時間に含めていません。\n");
    return out.toString();
  }
  private static String duration(long seconds){return (seconds/3600)+"時間"+(seconds%3600/60)+"分"+(seconds%60)+"秒";}
}
