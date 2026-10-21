package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_INVALID;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.MODEL_UNAVAILABLE;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PERMISSION_DENIED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.RECEIVE_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.ROUTE_UNAVAILABLE;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TRANSPORT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.USAGE_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.USAGE_UNAVAILABLE;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * ChatGPTとの連携に必要な計画イベント配信文脈の組み立てを扱う。
 *
 * <p>Text stays in memory until a validated response.completed event allows publication.
 */
public final class PlanSseAssembler {
  /** ChatGPTとの連携における状態の選択肢。 */
  public enum State {
    /** 完了。 */
    COMPLETED,
    /** 未完了。 */
    INCOMPLETE,
    /** 失敗。 */
    FAILED,
    /** 最終結果不明。 */
    OUTCOME_UNKNOWN
  }

  /**
   * ChatGPTとの連携で受け渡す結果を表す。
   *
   * @param state 状態
   * @param text テキスト
   * @param totalTokens 使用トークン数の合計
   * @param responseId 応答識別子
   * @param error エラー
   * @param diagnostic 診断
   */
  public record Result(
      State state,
      String text,
      Integer totalTokens,
      String responseId,
      ChatGptException.Code error,
      String diagnostic) {
    /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Result(
        State state,
        String text,
        Integer totalTokens,
        String responseId,
        ChatGptException.Code error) {
      this(
          state,
          text,
          totalTokens,
          responseId,
          error,
          "textLength=" + (text == null ? 0 : text.length()) + ", textSource=unavailable");
    }

    /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Result[state="
          + state
          + ", totalTokens="
          + totalTokens
          + ", error="
          + error
          + ", "
          + diagnostic
          + "]";
    }
  }

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  /** 受信。 */
  private int received;

  /** 部分一覧。 */
  private final SortedMap<PartKey, Part> parts = new TreeMap<>();

  /** 差分イベント一覧／完了イベント一覧／項目完了イベント一覧／終端項目一覧／メッセージ一覧／推論／内容部分一覧。 */
  private int deltaEvents,
      doneEvents,
      itemDoneEvents,
      terminalItems,
      messages,
      reasoning,
      contentParts;

  /**
   * ChatGPTとの連携で受け渡す部分キーを表す。
   *
   * @param output 出力
   * @param content 内容
   */
  private record PartKey(int output, int content) implements Comparable<PartKey> {
    /** ChatGPTとの連携で用いる比較終点を返す。 */
    public int compareTo(PartKey other) {
      int order = Integer.compare(output, other.output);
      return order != 0 ? order : Integer.compare(content, other.content);
    }
  }

  /** ChatGPTとの連携に必要な部分を扱う。 */
  private static final class Part {
    /** テキスト。 */
    final StringBuilder text = new StringBuilder();

    /** 完了。 */
    boolean done;
  }

  /** ChatGPTとの連携で用いる診断を返す。 */
  private String diagnostic(String text, String source) {
    return "terminalItems="
        + terminalItems
        + ", message="
        + messages
        + ", reasoning="
        + reasoning
        + ", output_text="
        + contentParts
        + ", deltaEvents="
        + deltaEvents
        + ", doneEvents="
        + doneEvents
        + ", itemDoneEvents="
        + itemDoneEvents
        + ", streamParts="
        + parts.size()
        + ", streamTextLength="
        + parts.values().stream().mapToInt(p -> p.text.length()).sum()
        + ", textLength="
        + text.length()
        + ", textSource="
        + source
        + ", receivedBytes="
        + received;
  }

  /** ChatGPTとの連携で用いる結果を返す。 */
  private Result result(
      State state,
      String text,
      Integer usage,
      String id,
      ChatGptException.Code error,
      String source) {
    return new Result(state, text, usage, id, error, diagnostic(text, source));
  }

  /** ChatGPTとの連携で用いる位置を返す。 */
  private static int index(JsonNode node, String field) {
    var value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
      throw ChatGptException.of(PROTOCOL);
    return value.intValue();
  }

  /** ChatGPTとの連携に関するテキスト部分を処理する。 */
  private void textPart(PartKey key, JsonNode text, boolean done) {
    if (!text.isTextual()) throw ChatGptException.of(PROTOCOL);
    var part = parts.computeIfAbsent(key, unused -> new Part());
    if (part.done && (!done || !part.text.toString().equals(text.textValue())))
      throw ChatGptException.of(PROTOCOL);
    if (done) {
      part.text.setLength(0);
      part.done = true;
    } else if (part.done) throw ChatGptException.of(PROTOCOL);
    part.text.append(text.textValue());
  }

  /** ChatGPTとの連携に関する項目を処理する。 */
  private void item(JsonNode item, Integer outputIndex) {
    String kind = item.path("type").asText();
    if (kind.equals("reasoning")) {
      if (outputIndex == null) reasoning++;
      return;
    }
    if (!kind.equals("message") || !item.path("content").isArray())
      throw ChatGptException.of(PROTOCOL);
    if (item.has("role") && !item.path("role").asText().equals("assistant"))
      throw ChatGptException.of(PROTOCOL);
    if (item.has("status") && !item.path("status").asText().equals("completed"))
      throw ChatGptException.of(PROTOCOL);
    if (outputIndex == null) messages++;
    int contentIndex = 0;
    for (var content : item.path("content")) {
      if (!content.path("type").asText().equals("output_text") || !content.path("text").isTextual())
        throw ChatGptException.of(PROTOCOL);
      if (outputIndex == null) contentParts++;
      else textPart(new PartKey(outputIndex, contentIndex), content.path("text"), true);
      contentIndex++;
    }
  }

  /** ChatGPTとの連携で用いる受信バイト数を返す。 */
  int receivedBytes() {
    return received;
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public Result read(InputStream input, int maxBytes) {
    return read(input, maxBytes, false);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  Result read(InputStream input, int maxBytes, boolean requireSseFraming) {
    var line = new ByteArrayOutputStream();
    var data = new StringBuilder();
    String event = "";
    received = 0;
    parts.clear();
    deltaEvents =
        doneEvents = itemDoneEvents = terminalItems = messages = reasoning = contentParts = 0;
    boolean seenData = false;
    boolean cr = false;
    try {
      for (int b; (b = input.read()) != -1; ) {
        if (++received > maxBytes) throw ChatGptException.of(RECEIVE_LIMIT);
        if (cr && b == '\n') {
          cr = false;
          continue;
        }
        cr = false;
        if (b == '\n' || b == '\r') {
          cr = b == '\r';
          String text = decode(line.toByteArray());
          line.reset();
          if (requireSseFraming && !isSseLine(text)) throw ChatGptException.of(PROTOCOL);
          if (text.isEmpty()) {
            if (!data.isEmpty()) {
              var result = parse(event, data.toString());
              if (result != null) return result;
            }
            data.setLength(0);
            event = "";
          } else if (text.startsWith("data:")) {
            seenData = true;
            if (!data.isEmpty()) data.append('\n');
            data.append(text.substring(5).replaceFirst("^ ", ""));
          } else if (text.startsWith("event:")) event = text.substring(6).strip();
        } else line.write(b);
      }
      if (requireSseFraming && (!seenData || !isSseLine(decode(line.toByteArray()))))
        throw ChatGptException.of(PROTOCOL);
      return result(State.OUTCOME_UNKNOWN, "", null, null, null, "none");
    } catch (ChatGptException e) {
      throw e;
    } catch (IOException e) {
      return result(State.OUTCOME_UNKNOWN, "", null, null, TRANSPORT, "none");
    } finally {
      parts.clear();
    }
  }

  /** ChatGPTとの連携において、イベント配信行かどうかを判定する。 */
  private static boolean isSseLine(String line) {
    return line.isEmpty()
        || line.startsWith(":")
        || line.startsWith("data:")
        || line.startsWith("event:")
        || line.startsWith("id:")
        || line.startsWith("retry:");
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private Result parse(String event, String data) {
    try {
      var node = json.readTree(data);
      String type = node.path("type").asText();
      if (!event.isEmpty() && !event.equals(type)) throw ChatGptException.of(PROTOCOL);
      if (type.equals("response.output_text.delta") || type.equals("response.output_text.done")) {
        boolean done = type.equals("response.output_text.done");
        textPart(
            new PartKey(index(node, "output_index"), index(node, "content_index")),
            node.path(done ? "text" : "delta"),
            done);
        if (done) doneEvents++;
        else deltaEvents++;
        return null;
      }
      if (type.equals("response.output_item.done")) {
        item(node.path("item"), index(node, "output_index"));
        itemDoneEvents++;
        return null;
      }
      if (type.equals("response.output_item.added")) {
        String kind = node.path("item").path("type").asText();
        if (!kind.equals("message") && !kind.equals("reasoning"))
          throw ChatGptException.of(PROTOCOL);
        return null;
      }
      var response = node.path("response");
      if (type.equals("response.failed") || type.equals("error")) {
        String code =
            type.equals("error")
                ? node.path("code").asText()
                : response.path("error").path("code").asText();
        return result(State.FAILED, "", usage(response), null, classify(code, 0), "none");
      }
      if (type.equals("response.incomplete"))
        return result(State.INCOMPLETE, "", usage(response), null, null, "none");
      if (!type.equals("response.completed")) return null;
      if (!response.path("status").asText().equals("completed")
          || !response.path("output").isArray()) throw ChatGptException.of(PROTOCOL);
      terminalItems = response.path("output").size();
      var text = new StringBuilder();
      for (var outputItem : response.path("output")) {
        item(outputItem, null);
        if (outputItem.path("type").asText().equals("message"))
          for (var content : outputItem.path("content"))
            text.append(content.path("text").textValue());
      }
      String source = "terminal";
      if (text.isEmpty()) {
        for (var part : parts.values()) text.append(part.text);
        source = text.isEmpty() ? "none" : "stream";
      }
      return result(
          State.COMPLETED,
          text.toString(),
          usage(response),
          response.path("id").asText(null),
          null,
          source);
    } catch (ChatGptException e) {
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  /** ChatGPTとの連携で用いる使用状況を返す。 */
  private Integer usage(JsonNode response) {
    var value = response.path("usage").path("total_tokens");
    return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
        ? value.intValue()
        : null;
  }

  /** 得られた情報を分類し、後続の判断で扱い分けられるようにする。 */
  static ChatGptException.Code classify(String code, int status) {
    return switch (code) {
      case "subscription_sharing_usage_limit_exceeded" -> USAGE_LIMIT;
      case "subscription_sharing_usage_unavailable" -> USAGE_UNAVAILABLE;
      case "model_not_found", "model_not_available" -> MODEL_UNAVAILABLE;
      case "subscription_sharing_invalid_user" -> AUTH_INVALID;
      case "subscription_sharing_user_not_eligible",
          "chatpass_v2_scope_not_authorized",
          "chatpass_v2_invalid_authorization_context" ->
          PERMISSION_DENIED;
      default ->
          switch (status) {
            case 401 -> AUTH_INVALID;
            case 403 -> PERMISSION_DENIED;
            case 429 -> USAGE_LIMIT;
            case 503 -> ROUTE_UNAVAILABLE;
            default -> PROTOCOL;
          };
    };
  }
}
