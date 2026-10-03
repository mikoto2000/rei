package dev.mikoto2000.rei.web;
public record SearchRequest(String query, Integer vectorTopK, Integer webTopK, Double threshold) {}
