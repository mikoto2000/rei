package dev.mikoto2000.rei.core.completion;

import dev.mikoto2000.rei.event.CredentialRedactor;
import org.jline.utils.*;

/** Bounded, redacted approval presentation shared by both terminal entry points. */
public final class ApprovalNotice {
  private ApprovalNotice() { }
  public static String text(String message) {
    String safe = CredentialRedactor.redact(message);
    var command = java.util.regex.Pattern.compile("(?m)^/approval approve ([0-9a-fA-F-]{36}) --continue$").matcher(safe);
    String id = command.find() ? command.group(1) : null;
    String[] lines = safe.split("\\R", -1);
    var result = new StringBuilder("+-- [APPROVAL REQUIRED] ----------------\n");
    result.append("| ").append(summary(lines.length > 0 ? lines[0] : "")).append('\n');
    if (lines.length > 1 && !lines[1].startsWith("/approval")) result.append("| ").append(summary(lines[1])).append('\n');
    result.append(id == null ? "/approval list" : "/approval show " + id).append('\n');
    if (id != null) {
      result.append("/approval approve ").append(id).append(" --continue\n");
      result.append("/approval deny ").append(id).append('\n');
    }
    return result.append("+--------------------------------------").toString();
  }
  private static String summary(String text) {
    String safe = text.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
    int count = safe.codePointCount(0, safe.length());
    return count <= 120 ? safe : safe.substring(0, safe.offsetByCodePoints(0, 117)) + "...";
  }
  public static AttributedString format(String message) {
    return new AttributedString(text(message), AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
  }
}
