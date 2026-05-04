package dev.mikoto2000.rei.bluesky;

public record BlueskyPostResult(
    boolean success,
    String message,
    String postUri,
    String postUrl) {
}
