package dev.mikoto2000.rei.paper;

import java.util.*;

public interface PaperRepository {
  Paper save(Paper paper, PaperOperation op);

  Optional<Paper> find(String id);

  List<Paper> search(String query, int limit);

  void remove(String id, PaperOperation op);

  void delete(String id, PaperOperation op);

  void beginPurge(String id, PaperOperation op);

  List<String> artifactIds(String id);

  String canonicalId(String id);

  record Version(String id, String kind, String cacheKey, String createdAt) {}

  List<Version> versions(String id);

  Optional<String> artifact(String paperId, String kind, String cacheKey);

  void saveArtifact(
      String paperId, String kind, String cacheKey, String content, PaperOperation op);
}
