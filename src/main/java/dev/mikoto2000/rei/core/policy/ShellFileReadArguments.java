package dev.mikoto2000.rei.core.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Detects direct shell readers as a planning error, not as a shell security boundary. */
public final class ShellFileReadArguments {
  private ShellFileReadArguments() {}
  private static final int MAX_CHARS = 1_048_576;
  private static final int MAX_WRAPPERS = 8;
  private static final Set<String> READERS = Set.of("get-content", "gc", "cat", "type", "tac", "head", "tail", "more", "less");
  private static final Set<String> SHELLS = Set.of("powershell", "pwsh", "cmd", "sh", "bash", "dash", "zsh");
  private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_CHARS).build())
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final String GUIDANCE = "runCommand: ファイル内容の読み込みには readMultiFile を使用してください。"
      + " 単一ファイルも {\"files\":[{\"path\":\"プロジェクト相対パス\"}]} で指定します。"
      + " truncated=true の続きは返された nextRead（expectedVersion・offset 等を含む）を改変せず files 配列に渡してください。"
      + " ページ分割や出力上限の回避に shell を使わないでください。コマンドは実行していません。"
      + " 同じバッチの成功済み呼び出しは繰り返さず、不正な呼び出しだけを修正してください。";

  public static boolean supports(String tool) { return "runCommand".equals(tool); }

  public static void validate(String tool, String input) {
    if (!supports(tool)) return;
    String command;
    try {
      if (input == null || input.length() > MAX_CHARS) throw new IllegalArgumentException();
      var root = JSON.readTree(input);
      if (root == null || !root.isObject() || !root.path("request").isObject()
          || !root.path("request").path("command").isString()) throw new IllegalArgumentException();
      command = root.path("request").path("command").asString();
    } catch (RuntimeException invalid) {
      throw new InvalidToolArgumentsException("runCommand: request.command must be a string in one JSON object, "
          + "for example {\"request\":{\"command\":\"git status --short\"}}; duplicate keys and oversized arguments are not allowed");
    }
    validateCommand(command);
  }

  /** Also used at the direct Tools boundary; never rewrites or executes a command. */
  public static void validateCommand(String command) {
    if (command != null && command.length() > MAX_CHARS)
      throw new InvalidToolArgumentsException("runCommand: command exceeds the argument size limit");
    if (command != null && containsReader(command, 0)) throw new InvalidToolArgumentsException(GUIDANCE);
  }

  private static boolean containsReader(String command, int depth) {
    if (depth > MAX_WRAPPERS) return false;
    List<Token> tokens = tokenize(command);
    boolean executable = true;
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.separator()) { executable = true; continue; }
      if (!executable) continue;
      String value = token.text();
      // Shell variable assignments and PowerShell's assignment RHS are command positions.
      if (value.equals("=") || value.matches("[A-Za-z_$][A-Za-z0-9_$:]*=")) continue;
      if (value.startsWith("$") && i + 1 < tokens.size() && tokens.get(i + 1).text().equals("=")) continue;
      if (value.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) continue;
      executable = false;
      String name = executableName(value);
      // A bare quoted PowerShell string is data, while & 'Get-Content' invokes it.
      boolean invoked = !token.quoted() || (i > 0 && tokens.get(i - 1).text().equals("&"));
      if (invoked && READERS.contains(name) && readsFile(name, tokens, i + 1)) return true;
      if (SHELLS.contains(name)) {
        for (int j = i + 1; j < tokens.size() && !tokens.get(j).separator(); j++) {
          String option = tokens.get(j).text().toLowerCase(Locale.ROOT);
          if (Set.of("-command", "-c", "-lc", "-ic", "/c", "/k").contains(option) && j + 1 < tokens.size()) {
            Token script = tokens.get(j + 1);
            if (script.quoted()) {
              if (containsReader(script.text(), depth + 1)) return true;
            } else {
              // Unquoted command arguments retain their original quote/operator syntax.
              if (containsReader(command.substring(script.start()), depth + 1)) return true;
            }
            break;
          }
        }
      }
    }
    return false;
  }

  private static boolean readsFile(String name, List<Token> tokens, int start) {
    if (name.equals("get-content") || name.equals("gc")) return true;
    boolean optionValue = false;
    for (int i = start; i < tokens.size() && !tokens.get(i).separator(); i++) {
      String arg = tokens.get(i).text();
      if (arg.equals("<")) return true;
      if (arg.equals(">") || arg.equals(">>")) { i++; continue; }
      if (arg.matches("[0-9]+") && i + 1 < tokens.size()
          && Set.of(">", ">>").contains(tokens.get(i + 1).text())) { i += 2; continue; }
      if (optionValue) { optionValue = false; continue; }
      if (arg.equals("--")) return i + 1 < tokens.size() && !tokens.get(i + 1).separator();
      if (Set.of("-n", "-c", "--lines", "--bytes", "--sleep-interval", "--pid").contains(arg)) { optionValue = true; continue; }
      if (arg.startsWith("-") || (name.equals("type") && arg.equalsIgnoreCase("/p"))) continue;
      return true;
    }
    // cat/head/tail used only as stdin filters remain useful for build or test output.
    return false;
  }

  private static String executableName(String value) {
    value = value.replace('\\', '/');
    value = value.substring(value.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return value.endsWith(".exe") ? value.substring(0, value.length() - 4) : value;
  }

  private record Token(String text, int start, boolean quoted, boolean separator) {}

  /** Quote-aware lexical scan; argument text such as echo "cat file" is never an executable. */
  private static List<Token> tokenize(String script) {
    var tokens = new ArrayList<Token>();
    for (int i = 0; i < script.length();) {
      char c = script.charAt(i);
      if (c == '\r' || c == '\n' || ";|&(){}".indexOf(c) >= 0) {
        tokens.add(new Token(String.valueOf(c), i++, false, true)); continue;
      }
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == '#') { while (i < script.length() && script.charAt(i) != '\n') i++; continue; }
      if (c == '=') { tokens.add(new Token("=", i++, false, false)); continue; }
      if (c == '<' || c == '>') {
        int start = i++;
        if (i < script.length() && script.charAt(i) == c) i++;
        tokens.add(new Token(script.substring(start, i), start, false, false)); continue;
      }
      int start = i;
      boolean quoted = false;
      var text = new StringBuilder();
      while (i < script.length()) {
        c = script.charAt(i);
        if (Character.isWhitespace(c) || ";|&(){}<>".indexOf(c) >= 0) break;
        if (c == '=' && script.charAt(start) == '$') break;
        if (c == '\'' || c == '"') {
          char quote = c; quoted = true; i++;
          while (i < script.length()) {
            c = script.charAt(i++);
            if (c == quote) {
              if (quote == '\'' && i < script.length() && script.charAt(i) == quote) { text.append(quote); i++; continue; }
              break;
            }
            if (c == '`' && quote == '"' && i < script.length()) c = script.charAt(i++);
            else if (c == '\\' && quote == '"' && i < script.length() && script.charAt(i) == '"') c = script.charAt(i++);
            text.append(c);
          }
        } else if ((c == '`' || c == '\\') && i + 1 < script.length()
            && (Character.isWhitespace(script.charAt(i + 1)) || "'\";|&(){}".indexOf(script.charAt(i + 1)) >= 0)) {
          text.append(script.charAt(i + 1)); i += 2;
        } else { text.append(c); i++; }
      }
      tokens.add(new Token(text.toString(), start, quoted, false));
    }
    return tokens;
  }
}
