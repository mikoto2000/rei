package dev.mikoto2000.rei.ui.shell.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** チャット応答読み上げの案内の結果と状態の契約を検証するテスト。 */
class ChatResponseNarratorTest {

  /** 検証に使う音通知通知サービス。 */
  private SoundNotificationService soundNotificationService;

  /** 検証に使う読み上げの案内。 */
  private ChatResponseNarrator narrator;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    soundNotificationService = mock(SoundNotificationService.class);
    narrator = new ChatResponseNarrator(soundNotificationService);
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void narrateIfCompleted_withNonEmptyText_callsNotify() {
    narrator.narrateIfCompleted("Hello, world!");

    verify(soundNotificationService).notify("Hello, world!");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void narrateIfCompleted_withNonEmptyText_setsWasNarratedTrue() {
    narrator.narrateIfCompleted("Hello, world!");

    assertThat(narrator.wasNarrated()).isTrue();
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void narrateIfCompleted_withEmptyString_doesNotCallNotify() {
    narrator.narrateIfCompleted("");

    verifyNoInteractions(soundNotificationService);
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void narrateIfCompleted_withEmptyString_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("");

    assertThat(narrator.wasNarrated()).isFalse();
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void narrateIfCompleted_withBlankString_doesNotCallNotify() {
    narrator.narrateIfCompleted("   ");

    verifyNoInteractions(soundNotificationService);
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void narrateIfCompleted_withBlankString_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("   ");

    assertThat(narrator.wasNarrated()).isFalse();
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void reset_afterNarration_setsWasNarratedFalse() {
    narrator.narrateIfCompleted("Some text");
    assertThat(narrator.wasNarrated()).isTrue();

    narrator.reset();

    assertThat(narrator.wasNarrated()).isFalse();
  }

  /** 無効を返すことを確認する。 */
  @Test
  void wasNarrated_initialState_returnsFalse() {
    assertThat(narrator.wasNarrated()).isFalse();
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void narrateIfCompleted_stripsEmojiBeforeNotify() {
    // 絵文字を含むテキスト — notify には絵文字が除去された文字列が渡される
    narrator.narrateIfCompleted("こんにちは🎉世界");

    verify(soundNotificationService).notify("こんにちは世界");
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void narrateIfCompleted_stripsControlCharactersBeforeNotify() {
    // NUL 文字（\x00）を含むテキスト
    narrator.narrateIfCompleted("テスト\u0000メッセージ");

    verify(soundNotificationService).notify("テストメッセージ");
  }

  /** 改行を保持することを確認する。 */
  @Test
  void narrateIfCompleted_preservesNewlineAndTab() {
    // 改行・タブは残す
    narrator.narrateIfCompleted("行1\n行2\tタブ");

    verify(soundNotificationService).notify("行1\n行2\tタブ");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesEmoji() {
    assertThat(ChatResponseNarrator.sanitize("hello🔧world")).isEqualTo("helloworld");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesNulCharacter() {
    assertThat(ChatResponseNarrator.sanitize("a\u0000b")).isEqualTo("ab");
  }

  /** 利用可能な形式への調整について、日本語を保持することを確認する。 */
  @Test
  void sanitize_preservesJapanese() {
    assertThat(ChatResponseNarrator.sanitize("日本語テスト")).isEqualTo("日本語テスト");
  }

  /** 利用可能な形式への調整について、改行を保持することを確認する。 */
  @Test
  void sanitize_preservesNewlineAndTab() {
    assertThat(ChatResponseNarrator.sanitize("a\nb\tc")).isEqualTo("a\nb\tc");
  }

  /** 利用可能な形式への調整について、コードまとまりを取り除くことを確認する。 */
  @Test
  void sanitize_removesCodeBlock() {
    assertThat(ChatResponseNarrator.sanitize("前の文\n```python\nprint('hello')\n```\n後の文"))
        .isEqualTo("前の文\n\n後の文");
  }

  /** 利用可能な形式への調整について、コードまとまりを取り除くことを確認する。 */
  @Test
  void sanitize_removesCodeBlockWithLanguageTag() {
    assertThat(ChatResponseNarrator.sanitize("説明\n```java\nint x = 1;\n```\n以上"))
        .isEqualTo("説明\n\n以上");
  }

  /** 利用可能な形式への調整について、複数コード防ぐを取り除くことを確認する。 */
  @Test
  void sanitize_removesMultipleCodeBlocks() {
    assertThat(ChatResponseNarrator.sanitize("A\n```\ncode1\n```\nB\n```\ncode2\n```\nC"))
        .isEqualTo("A\n\nB\n\nC");
  }

  /** ターミナルの音通知で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void sanitize_preservesInlineCode() {
    // バッククォート1つのインラインコードはテキストを残してバッククォートを除去する
    assertThat(ChatResponseNarrator.sanitize("変数 `x` を使います")).isEqualTo("変数 x を使います");
  }

  /** 利用可能な形式への調整について、見出しを取り除くことを確認する。 */
  @Test
  void sanitize_removesHeading() {
    assertThat(ChatResponseNarrator.sanitize("# タイトル")).isEqualTo("タイトル");
    assertThat(ChatResponseNarrator.sanitize("## セクション")).isEqualTo("セクション");
    assertThat(ChatResponseNarrator.sanitize("### 小見出し")).isEqualTo("小見出し");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesBold() {
    assertThat(ChatResponseNarrator.sanitize("**太字**テキスト")).isEqualTo("太字テキスト");
    assertThat(ChatResponseNarrator.sanitize("__太字__テキスト")).isEqualTo("太字テキスト");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesItalic() {
    assertThat(ChatResponseNarrator.sanitize("*斜体*テキスト")).isEqualTo("斜体テキスト");
    assertThat(ChatResponseNarrator.sanitize("_斜体_テキスト")).isEqualTo("斜体テキスト");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesBlockquote() {
    assertThat(ChatResponseNarrator.sanitize("> 引用テキスト")).isEqualTo("引用テキスト");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_removesHorizontalRule() {
    assertThat(ChatResponseNarrator.sanitize("前\n---\n後")).isEqualTo("前\n\n後");
    assertThat(ChatResponseNarrator.sanitize("前\n***\n後")).isEqualTo("前\n\n後");
  }

  /** 利用可能な形式への調整について、保存テーブル行一覧を保持することを確認する。 */
  @Test
  void sanitize_preservesTableRows() {
    assertThat(ChatResponseNarrator.sanitize("前\n| col1 | col2 |\n| --- | --- |\n後"))
        .isEqualTo("前\n| col1 | col2 |\n| --- | --- |\n後");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_convertsLink() {
    assertThat(ChatResponseNarrator.sanitize("[リンクテキスト](https://example.com)"))
        .isEqualTo("リンクテキスト");
  }

  /** ターミナルの音通知で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void sanitize_preservesBulletListWithDash() {
    // - で始まる箇条書きはそのまま残す
    assertThat(ChatResponseNarrator.sanitize("- 項目1\n- 項目2")).isEqualTo("- 項目1\n- 項目2");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sanitize_convertsBulletListAsteriskToDash() {
    // * で始まる箇条書きは - に置換して残す
    assertThat(ChatResponseNarrator.sanitize("* 項目1\n* 項目2")).isEqualTo("- 項目1\n- 項目2");
  }
}
