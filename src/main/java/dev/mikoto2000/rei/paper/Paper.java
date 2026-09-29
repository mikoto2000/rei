package dev.mikoto2000.rei.paper;

import java.util.*;

public record Paper(
    String id,
    String title,
    List<String> authors,
    String abstractText,
    Integer publicationYear,
    String venue,
    String doi,
    String arxivId,
    String openAlexId,
    Long citationCount,
    Boolean openAccess,
    String landingPageUrl,
    String pdfUrl,
    List<String> tags,
    String publishedAt,
    List<String> licenses) {
  public Paper {
    authors = authors == null ? List.of() : List.copyOf(authors);
    tags = tags == null ? List.of() : List.copyOf(tags);
    licenses = licenses == null ? List.of() : List.copyOf(licenses);
  }

  public Paper(
      String id,
      String title,
      List<String> authors,
      String abstractText,
      Integer publicationYear,
      String venue,
      String doi,
      String arxivId,
      String openAlexId,
      Long citationCount,
      Boolean openAccess,
      String landingPageUrl,
      String pdfUrl,
      List<String> tags) {
    this(
        id,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        null,
        List.of());
  }

  public Paper publication(String date, List<String> license) {
    return new Paper(
        id,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        date,
        license);
  }

  public Paper withId(String value) {
    return new Paper(
        value,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        publishedAt,
        licenses);
  }
}
