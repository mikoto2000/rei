package dev.mikoto2000.rei.activity;
/** One immutable alias/group snapshot, so reload cannot mix two configuration generations. */
public record SummaryThemeConfiguration(ProjectNameNormalizer projects,SummaryThemeGroups groups) {}
