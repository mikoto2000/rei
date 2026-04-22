package dev.mikoto2000.rei.feed;

public record FeedHttpResponse(
    int statusCode,
    String body) {
}
