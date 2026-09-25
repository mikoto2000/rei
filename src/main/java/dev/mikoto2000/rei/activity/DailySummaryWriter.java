package dev.mikoto2000.rei.activity;
@FunctionalInterface
public interface DailySummaryWriter {DailySummary write(DailySummaryAggregate aggregate) throws Exception;}
