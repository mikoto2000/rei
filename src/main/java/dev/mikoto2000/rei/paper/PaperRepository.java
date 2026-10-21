package dev.mikoto2000.rei.paper;

import java.util.List;
import java.util.Optional;

/** 論文を保存・検索するための窓口。 */
public interface PaperRepository {
  /** 後から参照できるよう、指定された情報を保存する。 */
  Paper save(Paper paper, PaperOperation op);

  /** 対象を特定できるよう、論文保存と検索の窓口を探す。 */
  Optional<Paper> find(String id);

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  List<Paper> search(String query, int limit);

  /** 指定された対象を管理から取り除く。 */
  void remove(String id, PaperOperation op);

  /** 指定された対象を削除する。 */
  void delete(String id, PaperOperation op);

  /** 論文の調査と整理に関する開始保持期限を過ぎた情報の削除を処理する。 */
  void beginPurge(String id, PaperOperation op);

  /** 論文の調査と整理で用いる成果物識別子一覧を返す。 */
  List<String> artifactIds(String id);

  /** 論文の調査と整理で用いる正規識別子を返す。 */
  String canonicalId(String id);

  /**
   * 論文の調査と整理で受け渡す形式のバージョンを表す。
   *
   * @param id 識別子
   * @param kind 種類
   * @param cacheKey キャッシュキー
   * @param createdAt 作成日時
   */
  record Version(String id, String kind, String cacheKey, String createdAt) {}

  /** 論文の調査と整理で用いるversionsを返す。 */
  List<Version> versions(String id);

  /** 論文の調査と整理で用いる成果物を返す。 */
  Optional<String> artifact(String paperId, String kind, String cacheKey);

  /** 後から参照できるよう、成果物を保存する。 */
  void saveArtifact(
      String paperId, String kind, String cacheKey, String content, PaperOperation op);
}
