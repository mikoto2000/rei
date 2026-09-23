package dev.mikoto2000.rei.activity.behavior;

@FunctionalInterface
public interface BehaviorMessageGenerator {String generate(BehaviorNotification notification) throws Exception;}
