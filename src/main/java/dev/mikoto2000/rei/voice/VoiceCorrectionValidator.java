package dev.mikoto2000.rei.voice;

import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;

/** Edits use zero-based Unicode code point offsets in the original, with an exclusive end. */
public final class VoiceCorrectionValidator {
  private final ObjectMapper json = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern PROTECTED = Pattern.compile(
      "[0-9０-９]+(?:[.,:：/年月日時分秒個件枚円%％-][0-9０-９]*)*|[一二三四五六七八九十百千万億]+(?:[年月日時分秒個件枚円日つ])?|"
      + "https?://[^\\s、。]+|[A-Za-z]:[\\\\/][^\\s、。]+|(?:~|\\.{1,2})?[\\\\/][^\\s、。]+|[\\p{L}0-9_-]+[\\\\/][^\\s、。]+|"
      + "[A-Za-z0-9_.-]+\\.[A-Za-z0-9]+|`[^`]*`|--?[A-Za-z][A-Za-z0-9_-]*|"
      + "しない|しません|ない|ません|なく|禁止|不要|無効|否定|やめ|いいえ|明日|今日|昨日|来週|午前|午後");
  private static final Pattern OPERATION = Pattern.compile(
      "削除|消去|消して|消す|実行|停止|起動|終了|送信|投稿|公開|購入|支払|変更|移動|上書|保存|作成|作って|作る|直して|直す|修正|実装|更新|追加|設定|置き換|コピー|書き込|登録|予約|承認|許可|キャンセル|インストール|アンインストール|deploy|delete|remove|execute|\\b(?:run|git|rm|sudo|cmd|ls|ps|echo|curl|wget|cp|mv|mkdir|bash|sh|python|npm|mvn)\\b|powershell", Pattern.CASE_INSENSITIVE);
  private static final Set<String> RESPONSES = Set.of("はい","いいえ","承認","許可","拒否","確認","確定","キャンセル","yes","no","ok","approve","deny");
  public record Decision(String text, String reason, boolean confirmationRequired) {}
  public static String controlKey(String text) { return text.strip().replaceFirst("[。.!！]+$", "").toLowerCase(Locale.ROOT); }
  public static boolean controlOrReply(String text) { return controlKey(text).equals("実行を停止") || RESPONSES.contains(controlKey(text)); }
  public static boolean important(String text) { return OPERATION.matcher(text).find(); }
  public Decision fallback(String raw,String reason) { return new Decision(raw,reason,important(raw)); }
  public Decision validate(String raw,String output,int maxChars) {
    try {
      if(raw.codePointCount(0,raw.length())>maxChars || output==null || output.isBlank() || output.length()>Math.min(65536,maxChars*16+2048))
        return fallback(raw,"invalid_output");
      JsonNode root=json.readTree(output);
      requireFields(root,Set.of("status","text","edits","diagnostic"));
      if(!root.get("status").isTextual() || !root.get("text").isTextual() || !root.get("diagnostic").isTextual()
          || root.get("diagnostic").textValue().length()>200 || !root.get("edits").isArray())throw new IllegalArgumentException();
      String status=root.get("status").textValue(), text=root.get("text").textValue();
      var edits=root.get("edits");
      if(text.isBlank() || text.codePointCount(0,text.length())>maxChars || edits.size()>32
          || text.codePoints().anyMatch(c->Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))throw new IllegalArgumentException();
      if(status.equals("unchanged") || status.equals("uncertain")) {
        if(!text.equals(raw) || !edits.isEmpty())throw new IllegalArgumentException();
        return fallback(raw,status);
      }
      if(!status.equals("corrected") || text.equals(raw) || edits.isEmpty())throw new IllegalArgumentException();
      int count=raw.codePointCount(0,raw.length()), cursor=0;
      var rebuilt=new StringBuilder();
      for(var edit:edits) {
        requireFields(edit,Set.of("start","end","before","after"));
        if(!edit.get("start").isIntegralNumber() || !edit.get("start").canConvertToInt() || !edit.get("end").isIntegralNumber()
            || !edit.get("end").canConvertToInt() || !edit.get("before").isTextual() || !edit.get("after").isTextual())throw new IllegalArgumentException();
        int start=edit.get("start").intValue(), end=edit.get("end").intValue();
        if(start<cursor || end<start || end>count)throw new IllegalArgumentException();
        int a=raw.offsetByCodePoints(0,start), b=raw.offsetByCodePoints(0,end);
        if(!raw.substring(a,b).equals(edit.get("before").textValue()))throw new IllegalArgumentException();
        rebuilt.append(raw,raw.offsetByCodePoints(0,cursor),a).append(edit.get("after").textValue());cursor=end;
      }
      rebuilt.append(raw.substring(raw.offsetByCodePoints(0,cursor)));
      if(!rebuilt.toString().equals(text))throw new IllegalArgumentException();
      if(controlOrReply(raw) || controlOrReply(text) || text.stripLeading().startsWith("/")
          || !protectedParts(raw).equals(protectedParts(text)) || important(raw) || important(text))return fallback(raw,"protected_change");
      int distance=distance(raw,text);
      // Short katakana -> Latin technical names require several code point substitutions.
      if(distance>Math.max(10,count/3) || Math.abs(text.codePointCount(0,text.length())-count)>Math.max(4,count/4))
        return fallback(raw,"excessive_change");
      return new Decision(text,"corrected",false);
    } catch(Exception invalid) { return fallback(raw,"invalid_output"); }
  }
  private static void requireFields(JsonNode node,Set<String> fields) {
    if(node==null || !node.isObject() || node.size()!=fields.size())throw new IllegalArgumentException();
    for(String field:fields)if(!node.hasNonNull(field))throw new IllegalArgumentException();
  }
  private static List<String> protectedParts(String text) { return PROTECTED.matcher(text).results().map(java.util.regex.MatchResult::group).toList(); }
  static int distance(String left,String right) {
    int[] a=left.codePoints().toArray(),b=right.codePoints().toArray(),row=new int[b.length+1];
    for(int j=0;j<=b.length;j++)row[j]=j;
    for(int i=1;i<=a.length;i++){int diagonal=row[0];row[0]=i;
      for(int j=1;j<=b.length;j++){int prior=row[j];row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),diagonal+(a[i-1]==b[j-1]?0:1));diagonal=prior;}}
    return row[b.length];
  }
}
