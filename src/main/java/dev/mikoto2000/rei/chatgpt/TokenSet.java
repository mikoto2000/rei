package dev.mikoto2000.rei.chatgpt;

import java.util.Set;

/**
 * ChatGPTとの連携で受け渡すトークン集合を表す。
 *
 * <p>Credentials never use the generated record toString.
 *
 * @param clientId クライアントの識別子
 * @param subject 対象
 * @param email メールアドレス
 * @param idToken 識別子トークン
 * @param accessToken 認証用のアクセストークン
 * @param refreshToken 認証を更新するトークン
 * @param scopes 権限の範囲
 * @param expiresAt 有効期限
 */
public record TokenSet(
    String clientId,
    String subject,
    String email,
    String idToken,
    String accessToken,
    String refreshToken,
    Set<String> scopes,
    long expiresAt) {
  /** 受け取った情報からTokenSetを作成する。 */
  public TokenSet {
    scopes = Set.copyOf(scopes);
  }

  /** ChatGPTとの連携で用いるなしトークン数を返す。 */
  public TokenSet withoutTokens() {
    return new TokenSet(clientId, subject, email, null, null, null, Set.of(), 0);
  }

  /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
  @Override
  public String toString() {
    return "TokenSet[protected]";
  }
}
