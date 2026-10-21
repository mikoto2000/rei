package dev.mikoto2000.rei.paper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** SQLite論文を保存・検索するための窓口。 */
@Repository
public class SqlitePaperRepository implements PaperRepository {
  /** データベースへの接続。 */
  private final JdbcTemplate jdbc;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate tx;

  /** 指定された値や利用先を使って、SqlitePaperRepositoryを初期化する。 */
  public SqlitePaperRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS papers(id TEXT PRIMARY KEY,payload TEXT NOT NULL,doi TEXT,arxiv"
            + " TEXT,fingerprint TEXT,search_text TEXT NOT NULL,removed INTEGER NOT NULL DEFAULT"
            + " 0)");
    jdbc.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS paper_doi ON papers(doi) WHERE doi IS NOT NULL");
    jdbc.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS paper_arxiv ON papers(arxiv) WHERE arxiv IS NOT NULL");
    jdbc.execute("CREATE INDEX IF NOT EXISTS paper_fingerprint ON papers(fingerprint)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS paper_aliases(id TEXT PRIMARY KEY,canonical_id TEXT NOT NULL)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS paper_artifacts(id TEXT PRIMARY KEY,paper_id TEXT NOT NULL,kind"
            + " TEXT NOT NULL,cache_key TEXT NOT NULL,created_at TEXT NOT NULL,content TEXT NOT"
            + " NULL)");
    jdbc.execute(
        "CREATE INDEX IF NOT EXISTS paper_artifact_lookup ON"
            + " paper_artifacts(paper_id,kind,cache_key,created_at)");
  }

  /** 論文の調査と整理で用いるnormを返す。 */
  static String norm(String s) {
    return s == null
        ? ""
        : Normalizer.normalize(s, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .strip()
            .replaceAll("\\s+", " ");
  }

  /** 論文の調査と整理で用いる論文のDOIを返す。 */
  static String doi(String s) {
    String d =
        norm(s).replaceFirst("^https?://(dx\\.)?doi\\.org/", "").replaceFirst("^doi:\\s*", "");
    return d.isEmpty() ? null : d;
  }

  /** 論文の調査と整理で用いるarXivを返す。 */
  static String arxiv(String s) {
    if (s == null) return null;
    String a =
        s.replaceFirst("^https?://arxiv.org/(abs|pdf)/", "")
            .replaceFirst("(?i)^arxiv:", "")
            .replaceFirst("\\.pdf$", "")
            .replaceFirst("v[0-9]+$", "")
            .strip();
    return a.isEmpty() ? null : a;
  }

  /** 論文の調査と整理で用いる要求内容の照合値を返す。 */
  static String fingerprint(Paper p) {
    return p.title() == null || p.authors().isEmpty() || p.publicationYear() == null
        ? null
        : norm(p.title()) + "|" + norm(p.authors().getFirst()) + "|" + p.publicationYear();
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public synchronized Paper save(Paper incoming, PaperOperation op) {
    try {
      return tx.execute(
          status -> {
            op.check();
            String d = doi(incoming.doi()),
                a = arxiv(incoming.arxivId()),
                f = fingerprint(incoming);
            List<Paper> matches =
                jdbc.query(
                    "SELECT payload FROM papers WHERE (doi IS NOT NULL AND doi=?) OR (arxiv IS NOT"
                        + " NULL AND arxiv=?) OR (fingerprint IS NOT NULL AND fingerprint=?)",
                    (r, n) -> PaperJson.read(r.getString(1), Paper.class),
                    d,
                    a,
                    f);
            Paper old =
                matches.stream()
                    .filter(
                        p ->
                            Objects.equals(d, doi(p.doi())) && d != null
                                || Objects.equals(a, arxiv(p.arxivId())) && a != null)
                    .findFirst()
                    .orElseGet(
                        () ->
                            matches.stream()
                                .filter(
                                    p ->
                                        (d == null || p.doi() == null || d.equals(doi(p.doi())))
                                            && (a == null
                                                || p.arxivId() == null
                                                || a.equals(arxiv(p.arxivId()))))
                                .findFirst()
                                .orElse(null));
            if (old != null) {
              for (Paper duplicate : matches) {
                if (duplicate.id().equals(old.id())) continue;
                boolean identified =
                    d != null && d.equals(doi(duplicate.doi()))
                        || a != null && a.equals(arxiv(duplicate.arxivId()));
                if (!identified) continue;
                old = merge(old, duplicate, doi(duplicate.doi()), arxiv(duplicate.arxivId()));
                jdbc.update(
                    "UPDATE paper_artifacts SET paper_id=? WHERE paper_id=?",
                    old.id(),
                    duplicate.id());
                jdbc.update(
                    "UPDATE paper_aliases SET canonical_id=? WHERE canonical_id=?",
                    old.id(),
                    duplicate.id());
                jdbc.update(
                    "INSERT OR REPLACE INTO paper_aliases VALUES(?,?)", duplicate.id(), old.id());
                jdbc.update("DELETE FROM papers WHERE id=?", duplicate.id());
              }
            }
            Paper p = merge(old, incoming, d, a);
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM papers WHERE id=? AND removed=2", Integer.class, p.id())
                > 0)
              throw new PaperException(
                  PaperException.Code.LIBRARY_STORAGE_FAILED, "完全削除が保留中です。先に purge を再実行してください");
            String search =
                norm(
                    p.title()
                        + " "
                        + String.join(" ", p.authors())
                        + " "
                        + p.abstractText()
                        + " "
                        + p.venue()
                        + " "
                        + p.doi()
                        + " "
                        + p.publicationYear()
                        + " "
                        + String.join(" ", p.tags()));
            jdbc.update(
                "INSERT INTO papers(id,payload,doi,arxiv,fingerprint,search_text,removed)"
                    + " VALUES(?,?,?,?,?,?,0) ON CONFLICT(id) DO UPDATE SET"
                    + " payload=excluded.payload,doi=excluded.doi,arxiv=excluded.arxiv,fingerprint=excluded.fingerprint,search_text=excluded.search_text,removed=0",
                p.id(),
                PaperJson.write(p),
                p.doi(),
                p.arxivId(),
                fingerprint(p),
                search);
            op.check();
            return p;
          });
    } catch (PaperException | java.util.concurrent.CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.LIBRARY_STORAGE_FAILED, "論文の保存に失敗しました", e);
    }
  }

  /** 後続の処理で使う値を返す。 */
  private static <T> T value(T next, T old) {
    return next == null || next instanceof String s && s.isBlank() ? old : next;
  }

  /** 関連する情報をまとめて扱えるよう、SQLite論文保存と検索の窓口を統合する。 */
  private Paper merge(Paper old, Paper n, String d, String a) {
    if (old == null)
      return new Paper(
          UUID.randomUUID().toString(),
          n.title(),
          n.authors(),
          n.abstractText(),
          n.publicationYear(),
          n.venue(),
          d,
          a,
          n.openAlexId(),
          n.citationCount(),
          n.openAccess(),
          n.landingPageUrl(),
          n.pdfUrl(),
          n.tags(),
          n.publishedAt(),
          n.licenses());
    var tags = new LinkedHashSet<>(old.tags());
    tags.addAll(n.tags());
    return new Paper(
        old.id(),
        value(n.title(), old.title()),
        n.authors().isEmpty() ? old.authors() : n.authors(),
        value(n.abstractText(), old.abstractText()),
        value(n.publicationYear(), old.publicationYear()),
        value(n.venue(), old.venue()),
        value(d, old.doi()),
        value(a, old.arxivId()),
        value(n.openAlexId(), old.openAlexId()),
        value(n.citationCount(), old.citationCount()),
        value(n.openAccess(), old.openAccess()),
        value(n.landingPageUrl(), old.landingPageUrl()),
        value(n.pdfUrl(), old.pdfUrl()),
        List.copyOf(tags),
        value(n.publishedAt(), old.publishedAt()),
        n.licenses().isEmpty() ? old.licenses() : n.licenses());
  }

  /** 論文の調査と整理で用いる正規識別子を返す。 */
  public String canonicalId(String id) {
    return jdbc
        .query("SELECT canonical_id FROM paper_aliases WHERE id=?", (r, n) -> r.getString(1), id)
        .stream()
        .findFirst()
        .orElse(id);
  }

  /** 論文の調査と整理で用いる成果物識別子一覧を返す。 */
  public List<String> artifactIds(String id) {
    var ids = new ArrayList<String>();
    ids.add(id);
    ids.addAll(
        jdbc.query(
            "SELECT id FROM paper_aliases WHERE canonical_id=?", (r, n) -> r.getString(1), id));
    return List.copyOf(ids);
  }

  /** 対象を特定できるよう、SQLite論文保存と検索の窓口を探す。 */
  public Optional<Paper> find(String id) {
    try {
      return jdbc
          .query(
              "SELECT payload FROM papers WHERE id=? AND removed=0",
              (r, n) -> PaperJson.read(r.getString(1), Paper.class),
              canonicalId(id))
          .stream()
          .findFirst();
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.LIBRARY_READ_FAILED, "論文の読込に失敗しました", e);
    }
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Paper> search(String query, int limit) {
    if (limit < 1 || limit > 100)
      throw new PaperException(PaperException.Code.INVALID_QUERY, "件数は1〜100です");
    try {
      StringBuilder sql = new StringBuilder("SELECT payload FROM papers WHERE removed=0");
      List<Object> args = new ArrayList<>();
      for (String word : norm(query).split(" "))
        if (!word.isBlank()) {
          sql.append(" AND instr(search_text,?)>0");
          args.add(word);
        }
      sql.append(" ORDER BY rowid DESC LIMIT ?");
      args.add(limit);
      return jdbc.query(
          sql.toString(), (r, n) -> PaperJson.read(r.getString(1), Paper.class), args.toArray());
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.LIBRARY_READ_FAILED, "ライブラリ検索に失敗しました", e);
    }
  }

  /** 指定された対象を管理から取り除く。 */
  public void remove(String id, PaperOperation op) {
    mutation(
        () -> {
          if (jdbc.update("UPDATE papers SET removed=1 WHERE id=? AND removed=0", id) == 0)
            throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文がありません");
        },
        op);
  }

  /** 論文の調査と整理に関する開始保持期限を過ぎた情報の削除を処理する。 */
  public void beginPurge(String id, PaperOperation op) {
    mutation(
        () -> {
          if (jdbc.update("UPDATE papers SET removed=2 WHERE id=?", id) == 0)
            throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文がありません");
        },
        op);
  }

  /** 指定された対象を削除する。 */
  public void delete(String id, PaperOperation op) {
    mutation(
        () -> {
          jdbc.update("DELETE FROM paper_artifacts WHERE paper_id=?", id);
          jdbc.update("DELETE FROM paper_aliases WHERE canonical_id=?", id);
          if (jdbc.update("DELETE FROM papers WHERE id=?", id) == 0)
            throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文がありません");
        },
        op);
  }

  /** 論文の調査と整理に関するmutationを処理する。 */
  private void mutation(Runnable action, PaperOperation op) {
    try {
      tx.executeWithoutResult(
          s -> {
            op.check();
            action.run();
            op.check();
          });
    } catch (PaperException | java.util.concurrent.CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.LIBRARY_DELETE_FAILED, "ライブラリ更新に失敗しました", e);
    }
  }

  /** 論文の調査と整理で用いる成果物を返す。 */
  public Optional<String> artifact(String id, String kind, String key) {
    return jdbc
        .query(
            "SELECT content FROM paper_artifacts WHERE paper_id=? AND kind=? AND cache_key=? ORDER"
                + " BY created_at DESC,rowid DESC LIMIT 1",
            (r, n) -> r.getString(1),
            id,
            kind,
            key)
        .stream()
        .findFirst();
  }

  /** 論文の調査と整理で用いるversionsを返す。 */
  public List<Version> versions(String id) {
    return jdbc.query(
        "SELECT id,kind,cache_key,created_at FROM paper_artifacts WHERE paper_id=? ORDER BY rowid"
            + " DESC LIMIT 100",
        (r, n) -> new Version(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
        id);
  }

  /** 後から参照できるよう、成果物を保存する。 */
  public void saveArtifact(String id, String kind, String key, String content, PaperOperation op) {
    tx.executeWithoutResult(
        s -> {
          op.check();
          jdbc.update(
              "INSERT INTO paper_artifacts VALUES(?,?,?,?,?,?)",
              UUID.randomUUID().toString(),
              id,
              kind,
              key,
              java.time.Instant.now().toString(),
              content);
          op.check();
        });
  }
}
