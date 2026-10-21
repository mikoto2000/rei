package dev.mikoto2000.rei.feed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** フィードOPML取り込みのユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class FeedOpmlImportService {
  /** 最大ファイル大きさバイト数。 */
  public static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

  /** 入力の解釈。 */
  private final OpmlParser parser;

  /** フィードサービス。 */
  private final FeedService feedService;

  /** フィードの購読と収集で用いる取り込みファイルを返す。 */
  public FeedOpmlImportResult importFile(Path path) {
    // Parse the complete document before registration; registrations themselves are independent.
    List<OpmlSubscription> subscriptions = readSubscriptions(path);
    List<Feed> imported = new ArrayList<>();
    List<FeedOpmlImportResult.Entry> skipped = new ArrayList<>();
    List<FeedOpmlImportResult.Entry> failed = new ArrayList<>();
    for (OpmlSubscription subscription : subscriptions) {
      if (!isFeedUrl(subscription.xmlUrl())) {
        failed.add(
            new FeedOpmlImportResult.Entry(
                new OpmlSubscription("", "", "[invalid feed URL]", "", List.of()),
                "invalid feed URL"));
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

  /** 必要な内容を参照できるよう、購読一覧を読み取る。 */
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

  /** フィードの購読と収集で用いるtooLargeを返す。 */
  private OpmlImportException tooLarge() {
    return new OpmlImportException("OPML file exceeds 10 MiB limit");
  }

  /** フィードの購読と収集において、フィードURLかどうかを判定する。 */
  private boolean isFeedUrl(String value) {
    try {
      FeedUrlSafety.parse(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
