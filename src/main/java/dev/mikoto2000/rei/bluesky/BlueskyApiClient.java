package dev.mikoto2000.rei.bluesky;

import java.time.OffsetDateTime;
import java.util.List;

/** BlueskyAPIを外部の接続先へ依頼する窓口。 */
public interface BlueskyApiClient {

  /** Blueskyとの連携で用いる認証を返す。 */
  AuthResult authenticate(String handle, String appPassword);

  /** 新たな対象を利用できるよう、投稿を作成する。 */
  PostResult createPost(String accessJwt, String did, String text);

  /** 指定や設定に応じた対象を利用するため、操作対象を確定する。 */
  default String resolveHandle(String handle) {
    throw new UnsupportedOperationException("resolveHandle is not implemented");
  }

  /** Blueskyとの連携で参照する著者フィードを返す。 */
  default List<FeedPost> getAuthorFeed(String actorDid, int limit) {
    throw new UnsupportedOperationException("getAuthorFeed is not implemented");
  }

  /** Blueskyとの連携で参照する著者フィードを返す。 */
  default List<FeedPost> getAuthorFeed(String actorDid, int limit, String accessJwt) {
    return getAuthorFeed(actorDid, limit);
  }

  /** 新たな対象を利用できるよう、返信を作成する。 */
  default PostResult createReply(
      String accessJwt,
      String did,
      String text,
      String parentUri,
      String parentCid,
      String rootUri,
      String rootCid) {
    throw new UnsupportedOperationException("createReply is not implemented");
  }

  /** 指定や設定に応じた対象を利用するため、返信対象を確定する。 */
  default ReplyTarget resolveReplyTarget(String accessJwt, String targetPostUriOrUrl) {
    throw new UnsupportedOperationException("resolveReplyTarget is not implemented");
  }

  /**
   * Blueskyとの連携で受け渡す認証結果を表す。
   *
   * @param success 成功
   * @param accessJwt アクセスJwt
   * @param did 分散識別子
   */
  record AuthResult(boolean success, String accessJwt, String did) {}

  /**
   * Blueskyとの連携で受け渡す投稿結果を表す。
   *
   * @param success 成功
   * @param postUri 投稿URI
   */
  record PostResult(boolean success, String postUri) {}

  /**
   * Blueskyとの連携で受け渡すフィード投稿を表す。
   *
   * @param uri 参照先のURI
   * @param cid 内容の識別子
   * @param text テキスト
   * @param indexedAt indexed日時
   * @param repost 再投稿
   * @param reply 返信
   * @param rootUri 作業ルートURI
   * @param rootCid 作業ルート内容の識別子
   */
  record FeedPost(
      String uri,
      String cid,
      String text,
      OffsetDateTime indexedAt,
      boolean repost,
      boolean reply,
      String rootUri,
      String rootCid) {}

  /**
   * Blueskyとの連携で受け渡す返信対象を表す。
   *
   * @param parentUri 親URI
   * @param parentCid 親内容の識別子
   * @param rootUri 作業ルートURI
   * @param rootCid 作業ルート内容の識別子
   * @param targetText 対象テキスト
   */
  record ReplyTarget(
      String parentUri, String parentCid, String rootUri, String rootCid, String targetText) {}
}
