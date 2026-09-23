package dev.mikoto2000.rei.activity.behavior;

/** Only an authorized, structured assessment may reach the wording generator. */
public record BehaviorNotification(String episodeId,BehaviorAssessment assessment) {}
