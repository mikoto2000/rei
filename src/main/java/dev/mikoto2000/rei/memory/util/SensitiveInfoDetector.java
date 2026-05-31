package dev.mikoto2000.rei.memory.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class SensitiveInfoDetector {

  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE_JP = Pattern.compile("(?:0\\d{1,4}-?\\d{1,4}-?\\d{3,4})");
  private static final Pattern PHONE_INTL = Pattern.compile("\\+\\d{1,3}[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}");
  private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|passwd|secret|token|apikey|api_key)\\s*[:=]\\s*\\S+");
  private static final Pattern PEM = Pattern.compile("-----BEGIN (?:CERTIFICATE|[A-Z ]*PRIVATE KEY)-----");

  public boolean containsSensitiveInfo(String content) {
    return !detectPatterns(content).isEmpty();
  }

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
    if (SECRET_KEY.matcher(value).find()) {
      matched.add("SECRET_KEY");
    }
    if (PEM.matcher(value).find()) {
      matched.add("PEM");
    }
    return matched;
  }
}
