package dev.mikoto2000.rei.web;

/** Registered project identity and path, so clients can distinguish duplicate names. */
public record ProjectResponse(String id, String name, String path) {}
