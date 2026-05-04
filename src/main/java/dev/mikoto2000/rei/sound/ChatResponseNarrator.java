package dev.mikoto2000.rei.sound;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * ChatCommand の AI 回答テキストを SoundNotificationService を通じて音声読み上げするコンポーネント。
 * ChatCommand と ReiApplication の間で読み上げスキップフラグを共有するシングルトン Bean。
 */
@Component
@RequiredArgsConstructor
public class ChatResponseNarrator {

    private final SoundNotificationService soundNotificationService;

    private final AtomicBoolean narratedFlag = new AtomicBoolean(false);

    /**
     * 読み上げスキップフラグをリセットする。
     * ChatCommand の実行開始時に呼び出す。
     */
    public void reset() {
        narratedFlag.set(false);
    }

    /**
     * 回答テキストが非空の場合に音声読み上げを実行し、読み上げスキップフラグを設定する。
     * 読み上げ前に絵文字・制御文字・非表示文字を除去する。
     *
     * @param responseText AI 回答テキスト全文
     */
    public void narrateIfCompleted(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            narratedFlag.set(false);
            return;
        }
        String sanitized = sanitize(responseText);
        if (sanitized.isBlank()) {
            narratedFlag.set(false);
            return;
        }
        soundNotificationService.notify(sanitized);
        narratedFlag.set(true);
    }

    /**
     * 音声読み上げに不適切な文字・ブロックを除去する。
     * - Markdown コードブロック（```...``` で囲まれたブロック全体）
     * - 絵文字・記号文字（Unicode So カテゴリ）
     * - サロゲートペア文字（Cs カテゴリ）
     * - 未割当文字（Cn カテゴリ）
     * - 制御文字（改行・タブ・復帰以外）
     *
     * @param text 入力テキスト
     * @return サニタイズ済みテキスト
     */
    static String sanitize(String text) {
        return text
            // Markdown コードブロック（```...```）をブロックごと除去（DOTALL で改行をまたぐ）
            .replaceAll("```[\\s\\S]*?```", "")
            // 絵文字・記号・サロゲート・未割当文字を除去
            .replaceAll("[\\p{So}\\p{Cs}\\p{Cn}]", "")
            // 制御文字を除去（タブ \t、改行 \n、復帰 \r は残す）
            .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
    }

    /**
     * 読み上げスキップフラグを返す。
     *
     * @return 音声読み上げが実行された場合 true
     */
    public boolean wasNarrated() {
        return narratedFlag.get();
    }
}
