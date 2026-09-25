package dev.mikoto2000.rei.activity;
import java.util.*;
/** Final display granularity. memberProjects contains at most two observed representatives, never raw aliases. */
public record SummaryThemeCandidate(String id,String displayTheme,Level level,List<String> memberProjects,
    int memberProjectCount,List<String> activities,List<String> themes,long durationSeconds,int observationCount,
    long longestContinuousSeconds,double associationConfidence,int specificity,double groupCoverage,double score,String label) {
  public enum Level { GROUP, PROJECT, THEME, GENERIC }
}
