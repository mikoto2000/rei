package dev.mikoto2000.rei.text;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** テキストをモデルから呼び出せるツールとして提供する。 */
@Component
public class TextTools {

  /** 対象の規模を判断するため、文字数を数える。 */
  @Tool(
      name = "countCharacters",
      description =
          """
      文字列の文字数を取得します。
      Java の UTF-16 code unit 数ではなく Unicode code point 数を返します。
      絵文字などのサロゲートペアは 1 文字として数えます。
      @param text 文字数を数える対象の文字列
      @return Unicode code point ベースの文字数
      """)
  int countCharacters(String text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    return text.codePointCount(0, text.length());
  }
}
