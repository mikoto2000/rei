package dev.mikoto2000.rei.feed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FeedOpmlImportService {
  public static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

  private final OpmlParser parser;
  private final FeedService feedService;

  public FeedOpmlImportResult importFile(Path path) {
    // Parse the complete document before registration; registrations themselves are independent.
    List<OpmlSubscription> subscriptions = readSubscriptions(path);
    List<Feed> imported = new ArrayList<>();
    List<FeedOpmlImportResult.Entry> skipped = new ArrayList<>();
    List<FeedOpmlImportResult.Entry> failed = new ArrayList<>();
    for (OpmlSubscription subscription : subscriptions) {
      if (!isFeedUrl(subscription.xmlUrl())) {
        failed.add(new FeedOpmlImportResult.Entry(subscription, "invalid feed URL"));
        continue;
      }
      try {
        imported.add(feedService.add(subscription.xmlUrl(), subscription.displayName()));
      } catch (DuplicateFeedException e) {
        skipped.add(new FeedOpmlImportResult.Entry(subscription, "already registered"));
      } catch (RuntimeException e) {
        failed.add(new FeedOpmlImportResult.Entry(subscription, "feed registration failed"));
      }
    }
    return new FeedOpmlImportResult(imported, skipped, failed);
  }

  private List<OpmlSubscription> readSubscriptions(Path path) {
    try {
      if (!Files.isRegularFile(path)) {
        if (Files.notExists(path)) throw new OpmlImportException("file not found: " + path);
        throw new OpmlImportException("not a readable regular file: " + path);
      }
      if (Files.size(path) > MAX_FILE_SIZE_BYTES) throw tooLarge();
      try (var input = Files.newInputStream(path)) {
        // Bound the actual read as well, in case the file grows after the size check.
        byte[] bytes = input.readNBytes(MAX_FILE_SIZE_BYTES + 1);
        if (bytes.length > MAX_FILE_SIZE_BYTES) throw tooLarge();
        return parser.parse(new ByteArrayInputStream(bytes));
      }
    } catch (NoSuchFileException e) {
      throw new OpmlImportException("file not found: " + path, e);
    } catch (IOException | SecurityException e) {
      throw new OpmlImportException("cannot read file: " + path, e);
    }
  }

  private OpmlImportException tooLarge() {
    return new OpmlImportException("OPML file exceeds 10 MiB limit");
  }

  private boolean isFeedUrl(String value) {
    try {
      URI uri = URI.create(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
