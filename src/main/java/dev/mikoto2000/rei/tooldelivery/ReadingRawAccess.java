package dev.mikoto2000.rei.tooldelivery;

/** Host ports over the existing immutable raw-result store and its exact page parser. */
public record ReadingRawAccess(Save saver, Read reader, Parse parser) {
  @FunctionalInterface public interface Save { String save(String conversation, String run, String tool, String call, String body); }
  @FunctionalInterface public interface Read { String read(String conversation, String ref); }
  @FunctionalInterface public interface Parse { Page parse(String response); }
  public record Page(String rawResultRef, int offset, boolean hasMore, String content) {}
  public String save(String conversation, String run, String tool, String call, String body) { return saver.save(conversation, run, tool, call, body); }
  public String read(String conversation, String ref) { return reader.read(conversation, ref); }
  public Page parse(String response) { return parser.parse(response); }
}
