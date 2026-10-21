package dev.mikoto2000.rei.bluesky;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Bluesky投稿のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class BlueskyPostService {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(BlueskyPostService.class);

  /** この機能の動作設定。 */
  private final BlueskyProperties properties;

  /** BlueskyAPIクライアント。 */
  private final BlueskyApiClient blueskyApiClient;

  /** Bluesky返信テキスト生成。 */
  private final BlueskyReplyTextGenerator blueskyReplyTextGenerator;

  /** 会話保存と検索の窓口。 */
  private final BlueskyReplyConversationRepository conversationRepository;

  /** 返信状態保存と検索の窓口。 */
  private final BlueskyReplyStateRepository replyStateRepository;

  /** Blueskyとの連携で用いる投稿を返す。 */
  public BlueskyPostResult post(String text) {
    try {
      log.debug("Bluesky post requested: textLength={}", text == null ? null : text.length());
      BlueskyPostResult validation = validatePostRequest(text);
      if (validation != null) {
        return validation;
      }

      log.debug("Bluesky authenticating: handle={}", properties.getHandle());
      BlueskyApiClient.AuthResult authResult =
          blueskyApiClient.authenticate(properties.getHandle(), properties.getAppPassword());
      log.debug(
          "Bluesky authenticate result: success={}, hasAccessJwt={}, did={}",
          authResult.success(),
          authResult.accessJwt() != null,
          authResult.did());
      if (!authResult.success()) {
        log.warn("Bluesky post failed: authentication failed");
        return new BlueskyPostResult(false, "Bluesky authentication failed", null, null);
      }

      BlueskyApiClient.PostResult postResult =
          blueskyApiClient.createPost(authResult.accessJwt(), authResult.did(), text);
      log.debug(
          "Bluesky createPost result: success={}, postUri={}",
          postResult.success(),
          postResult.postUri());
      if (!postResult.success()) {
        log.warn("Bluesky post failed: createPost failed");
        return new BlueskyPostResult(false, "Bluesky post failed", null, null);
      }

      String postUrl = toPostUrl(postResult.postUri());
      return new BlueskyPostResult(true, "Bluesky post created", postResult.postUri(), postUrl);
    } catch (Exception e) {
      log.warn("Bluesky post failed due to unexpected error: {}", e.getMessage(), e);
      return new BlueskyPostResult(
          false, "Bluesky post failed due to unexpected error", null, null);
    }
  }

  /** Blueskyとの連携で用いる返信を返す。 */
  public BlueskyPostResult reply(String targetPostUriOrUrl, String text) {
    try {
      log.debug(
          "Bluesky reply requested: target={}, textLength={}",
          targetPostUriOrUrl,
          text == null ? null : text.length());
      BlueskyPostResult validation = validatePostRequest(text);
      if (validation != null) {
        return validation;
      }
      if (isBlank(targetPostUriOrUrl)) {
        return new BlueskyPostResult(false, "Target post URI/URL must not be blank", null, null);
      }

      BlueskyApiClient.AuthResult authResult =
          blueskyApiClient.authenticate(properties.getHandle(), properties.getAppPassword());
      if (!authResult.success()) {
        return new BlueskyPostResult(false, "Bluesky authentication failed", null, null);
      }

      BlueskyApiClient.ReplyTarget target =
          blueskyApiClient.resolveReplyTarget(authResult.accessJwt(), targetPostUriOrUrl);
      if (target == null
          || isBlank(target.parentUri())
          || isBlank(target.parentCid())
          || isBlank(target.rootUri())
          || isBlank(target.rootCid())) {
        return new BlueskyPostResult(false, "Failed to resolve reply target", null, null);
      }

      if (!replyStateRepository.tryClaimReply(target.parentUri())) {
        return new BlueskyPostResult(
            false, "A reply has already been sent or attempted for this post", null, null);
      }
      BlueskyApiClient.PostResult replyResult =
          blueskyApiClient.createReply(
              authResult.accessJwt(),
              authResult.did(),
              text,
              target.parentUri(),
              target.parentCid(),
              target.rootUri(),
              target.rootCid());
      if (!replyResult.success()) {
        replyStateRepository.releaseReplyClaim(target.parentUri());
        return new BlueskyPostResult(false, "Bluesky reply failed", null, null);
      }
      replyStateRepository.markReplied(
          target.parentUri(), conversationHandle(target), replyResult.postUri());
      appendConversation(target, text);
      String postUrl = toPostUrl(replyResult.postUri());
      return new BlueskyPostResult(true, "Bluesky reply created", replyResult.postUri(), postUrl);
    } catch (Exception e) {
      log.warn("Bluesky reply failed due to unexpected error: {}", e.getMessage(), e);
      return new BlueskyPostResult(
          false, "Bluesky reply failed due to unexpected error", null, null);
    }
  }

  /** Blueskyとの連携で用いる返信を返す。 */
  public BlueskyPostResult reply(String targetPostUriOrUrl) {
    try {
      if (!properties.isEnabled()) {
        return new BlueskyPostResult(false, "Bluesky posting is disabled", null, null);
      }
      if (isBlank(properties.getHandle()) || isBlank(properties.getAppPassword())) {
        return new BlueskyPostResult(false, "Bluesky credentials are not configured", null, null);
      }
      if (isBlank(targetPostUriOrUrl)) {
        return new BlueskyPostResult(false, "Target post URI/URL must not be blank", null, null);
      }
      BlueskyApiClient.AuthResult authResult =
          blueskyApiClient.authenticate(properties.getHandle(), properties.getAppPassword());
      if (!authResult.success()) {
        return new BlueskyPostResult(false, "Bluesky authentication failed", null, null);
      }
      BlueskyApiClient.ReplyTarget target =
          blueskyApiClient.resolveReplyTarget(authResult.accessJwt(), targetPostUriOrUrl);
      if (target == null
          || isBlank(target.parentUri())
          || isBlank(target.parentCid())
          || isBlank(target.rootUri())
          || isBlank(target.rootCid())) {
        return new BlueskyPostResult(false, "Failed to resolve reply target", null, null);
      }
      String generatedText =
          blueskyReplyTextGenerator.generateForManualReply(
              target.targetText(), conversationHandle(target), target.parentUri());
      return reply(targetPostUriOrUrl, generatedText);
    } catch (Exception e) {
      log.warn("Bluesky auto-reply failed due to unexpected error: {}", e.getMessage(), e);
      return new BlueskyPostResult(
          false, "Bluesky reply failed due to unexpected error", null, null);
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、投稿要求を検証する。 */
  private BlueskyPostResult validatePostRequest(String text) {
    if (!properties.isEnabled()) {
      log.debug("Bluesky post skipped: feature disabled");
      log.warn("Bluesky post skipped: feature disabled");
      return new BlueskyPostResult(false, "Bluesky posting is disabled", null, null);
    }
    if (isBlank(properties.getHandle()) || isBlank(properties.getAppPassword())) {
      log.debug(
          "Bluesky post skipped: credentials missing (handleBlank={}, appPasswordBlank={})",
          isBlank(properties.getHandle()),
          isBlank(properties.getAppPassword()));
      log.warn("Bluesky post skipped: credentials missing");
      return new BlueskyPostResult(false, "Bluesky credentials are not configured", null, null);
    }
    if (isBlank(text)) {
      log.debug("Bluesky post skipped: text is blank");
      log.warn("Bluesky post skipped: text is blank");
      return new BlueskyPostResult(false, "Post text must not be blank", null, null);
    }
    if (text.length() > properties.getMaxPostLength()) {
      log.debug(
          "Bluesky post skipped: text too long (length={}, max={})",
          text.length(),
          properties.getMaxPostLength());
      log.warn(
          "Bluesky post skipped: text too long (length={}, max={})",
          text.length(),
          properties.getMaxPostLength());
      return new BlueskyPostResult(
          false, "Post text exceeds max length: " + properties.getMaxPostLength(), null, null);
    }
    return null;
  }

  /** 利用先へ渡せるよう、投稿URLの形式に変換する。 */
  private String toPostUrl(String postUri) {
    if (postUri == null || postUri.isBlank()) {
      return null;
    }
    String[] parts = postUri.split("/");
    if (parts.length < 3) {
      return null;
    }
    String did = parts[2];
    String rkey = parts[parts.length - 1];
    return "https://bsky.app/profile/" + did + "/post/" + rkey;
  }

  /** Blueskyとの連携において、空白かどうかを判定する。 */
  private boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  /** 既存の記録へ経緯を残すため、会話を追記する。 */
  private void appendConversation(BlueskyApiClient.ReplyTarget target, String replyText) {
    String handle = conversationHandle(target);
    if (handle == null || handle.isBlank()) {
      return;
    }
    conversationRepository.appendUserMessage(
        handle, target.targetText() == null ? "" : target.targetText());
    conversationRepository.appendAssistantMessage(handle, replyText == null ? "" : replyText);
  }

  /** Blueskyとの連携で用いる会話操作対象を返す。 */
  private String conversationHandle(BlueskyApiClient.ReplyTarget target) {
    if (target == null || target.parentUri() == null || target.parentUri().isBlank()) {
      return null;
    }
    String[] parts = target.parentUri().split("/");
    if (parts.length < 3) {
      return null;
    }
    return parts[2];
  }
}
