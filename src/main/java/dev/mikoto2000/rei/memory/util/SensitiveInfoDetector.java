package dev.mikoto2000.rei.memory.util;

import com.google.re2j.Pattern;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 長期記憶の補助に必要な秘密を含む付随情報検出の窓口を扱う。 */
@Component
public class SensitiveInfoDetector {

  /** メールアドレス。 */
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** 電話番号国内形式。 */
  private static final Pattern PHONE_JP = Pattern.compile("(?:0\\d{1,4}-?\\d{1,4}-?\\d{3,4})");

  /** 電話番号国際形式。 */
  private static final Pattern PHONE_INTL =
      Pattern.compile("\\+\\d{1,3}[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}");

  /** 秘密情報キー。 */
  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "(?i)(password|passwd|secret|access[_-]?token|refresh[_-]?token|token|api[_-]?key|credential|authorization|accessJwt|refreshJwt)[\"']?\\s*[:=]\\s*[\"']?\\S+");

  /** BAREキー。 */
  private static final Pattern BARE_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b");

  /** 秘密鍵などのPEM情報を検出する規則。 */
  private static final Pattern PEM =
      Pattern.compile("-----BEGIN (?:CERTIFICATE|[A-Z ]*PRIVATE KEY)-----");

  /** 長期記憶の補助で判断に用いる含有秘密を含む付随情報の条件を確認する。 */
  public boolean containsSensitiveInfo(String content) {
    return !detectPatterns(content).isEmpty();
  }

  /** 長期記憶の補助で用いる検出照合規則一覧を返す。 */
  public List<String> detectPatterns(String content) {
    String value = content == null ? "" : content;
    List<String> matched = new ArrayList<>();
    if (EMAIL.matcher(value).find()) {
      matched.add("EMAIL");
    }
    if (PHONE_JP.matcher(value).find()) {
      matched.add("PHONE_JP");
    }
    if (PHONE_INTL.matcher(value).find()) {
      matched.add("PHONE_INTL");
    }
    if (SECRET_KEY.matcher(value).find() || BARE_KEY.matcher(value).find()) {
      matched.add("SECRET_KEY");
    }
    if (PEM.matcher(value).find()) {
      matched.add("PEM");
    }
    return matched;
  }
}
