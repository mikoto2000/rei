package dev.mikoto2000.rei.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatResponseNarratorTest {

  private SoundNotificationService soundNotificationService;
  private ChatResponseNarrator narrator;

  @BeforeEach
  void setUp() {
    soundNotificationService = mock(SoundNotificationService.class);
    narrator = new ChatResponseNarrator(soundNotificationService);
  }

  @Test
  void narrateIfCompleted_withNonEmptyText_callsNotify() {
    narrator.narrateIfCompleted("Hello, world!");

    verify(soundNotificationService).notify("Hello, world!");
  }

  @Test
  void narrateIfCompleted_withNonEmptyText_setsWasNarratedTrue() {
    narrator.narrateIfCompleted("Hello, world!");

    assertThat(narrator.wasNarrated()).isTrue();
  }

  @Test
  void narrateIfCompleted_withEmptyString_doesNotCallNotify() {
    narrator.narrateIfCompleted("");

    verifyNoInteractions(soundNotificationService);
  }

  @Test
  void narrateIfCompleted_withEmptyString_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("");

    assertThat(narrator.wasNarrated()).isFalse();
  }

  @Test
  void narrateIfCompleted_withBlankString_doesNotCallNotify() {
    narrator.narrateIfCompleted("   ");

    verifyNoInteractions(soundNotificationService);
  }

  @Test
  void narrateIfCompleted_withBlankString_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("   ");

    assertThat(narrator.wasNarrated()).isFalse();
  }

  @Test
  void reset_afterNarration_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("Some text");
    assertThat(narrator.wasNarrated()).isTrue();

    narrator.reset();

    assertThat(narrator.wasNarrated()).isFalse();
  }

  @Test
  void wasNarrated_initialState_returnsFalse() {
    assertThat(narrator.wasNarrated()).isFalse();
  }

  @Test
  void narrateIfCompleted_stripsEmojiBeforeNotify() {
    // 絵文字を含むテキスト — notify には絵文字が除去された文字列が渡される
    narrator.narrateIfCompleted("こんにちは🎉世界");

    verify(soundNotificationService).notify("こんにちは世界");
  }

  @Test
  void narrateIfCompleted_stripsControlCharactersBeforeNotify() {
    // NUL 文字（\x00）を含むテキスト
    narrator.narrateIfCompleted("テスト\u0000メッセージ");

    verify(soundNotificationService).notify("テストメッセージ");
  }

  @Test
  void narrateIfCompleted_preservesNewlineAndTab() {
    // 改行・タブは残す
    narrator.narrateIfCompleted("行1\n行2\tタブ");

    verify(soundNotificationService).notify("行1\n行2\tタブ");
  }

  @Test
  void sanitize_removesEmoji() {
    assertThat(ChatResponseNarrator.sanitize("hello🔧world")).isEqualTo("helloworld");
  }

  @Test
  void sanitize_removesNulCharacter() {
    assertThat(ChatResponseNarrator.sanitize("a\u0000b")).isEqualTo("ab");
  }

  @Test
  void sanitize_preservesJapanese() {
    assertThat(ChatResponseNarrator.sanitize("日本語テスト")).isEqualTo("日本語テスト");
  }

  @Test
  void sanitize_preservesNewlineAndTab() {
    assertThat(ChatResponseNarrator.sanitize("a\nb\tc")).isEqualTo("a\nb\tc");
  }

  @Test
  void sanitize_removesCodeBlock() {
    assertThat(ChatResponseNarrator.sanitize("前の文\n```python\nprint('hello')\n```\n後の文"))
        .isEqualTo("前の文\n\n後の文");
  }

  @Test
  void sanitize_removesCodeBlockWithLanguageTag() {
    assertThat(ChatResponseNarrator.sanitize("説明\n```java\nint x = 1;\n```\n以上"))
        .isEqualTo("説明\n\n以上");
  }

  @Test
  void sanitize_removesMultipleCodeBlocks() {
    assertThat(ChatResponseNarrator.sanitize("A\n```\ncode1\n```\nB\n```\ncode2\n```\nC"))
        .isEqualTo("A\n\nB\n\nC");
  }

  @Test
  void sanitize_preservesInlineCode() {
    // バッククォート1つのインラインコードはテキストを残してバッククォートを除去する
    assertThat(ChatResponseNarrator.sanitize("変数 `x` を使います")).isEqualTo("変数 x を使います");
  }

  @Test
  void sanitize_removesHeading() {
    assertThat(ChatResponseNarrator.sanitize("# タイトル")).isEqualTo("タイトル");
    assertThat(ChatResponseNarrator.sanitize("## セクション")).isEqualTo("セクション");
    assertThat(ChatResponseNarrator.sanitize("### 小見出し")).isEqualTo("小見出し");
  }

  @Test
  void sanitize_removesBold() {
    assertThat(ChatResponseNarrator.sanitize("**太字**テキスト")).isEqualTo("太字テキスト");
    assertThat(ChatResponseNarrator.sanitize("__太字__テキスト")).isEqualTo("太字テキスト");
  }

  @Test
  void sanitize_removesItalic() {
    assertThat(ChatResponseNarrator.sanitize("*斜体*テキスト")).isEqualTo("斜体テキスト");
    assertThat(ChatResponseNarrator.sanitize("_斜体_テキスト")).isEqualTo("斜体テキスト");
  }

  @Test
  void sanitize_removesBlockquote() {
    assertThat(ChatResponseNarrator.sanitize("> 引用テキスト")).isEqualTo("引用テキスト");
  }

  @Test
  void sanitize_removesHorizontalRule() {
    assertThat(ChatResponseNarrator.sanitize("前\n---\n後")).isEqualTo("前\n\n後");
    assertThat(ChatResponseNarrator.sanitize("前\n***\n後")).isEqualTo("前\n\n後");
  }

  @Test
  void sanitize_removesTableRows() {
    assertThat(ChatResponseNarrator.sanitize("前\n| col1 | col2 |\n| --- | --- |\n後"))
        .isEqualTo("前\n\n\n後");
  }

  @Test
  void sanitize_convertsLink() {
    assertThat(ChatResponseNarrator.sanitize("[リンクテキスト](https://example.com)"))
        .isEqualTo("リンクテキスト");
  }

  @Test
  void sanitize_preservesBulletListWithDash() {
    // - で始まる箇条書きはそのまま残す
    assertThat(ChatResponseNarrator.sanitize("- 項目1\n- 項目2")).isEqualTo("- 項目1\n- 項目2");
  }

  @Test
  void sanitize_convertsBulletListAsteriskToDash() {
    // * で始まる箇条書きは - に置換して残す
    assertThat(ChatResponseNarrator.sanitize("* 項目1\n* 項目2")).isEqualTo("- 項目1\n- 項目2");
  }
}
