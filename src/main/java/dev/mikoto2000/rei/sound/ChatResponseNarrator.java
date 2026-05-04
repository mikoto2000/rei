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
     * 音声読み上げに不適切な文字・ブロック・Markdown 記法を除去する。
     *
     * <p>除去・変換の内容:
     * <ul>
     *   <li>Markdown コードブロック（```...```）をブロックごと除去</li>
     *   <li>表（| で始まる行）を行ごと除去</li>
     *   <li>水平線（--- または *** のみの行）を行ごと除去</li>
     *   <li>見出し（# 〜 ######）の記号を除去してテキストを残す</li>
     *   <li>引用（> ）の記号を除去してテキストを残す</li>
     *   <li>箇条書き（* item）の * を - に置換して残す（- item はそのまま）</li>
     *   <li>太字・斜体（**text**、*text*、__text__、_text_）の記号を除去してテキストを残す</li>
     *   <li>インラインコード（`code`）のバッククォートを除去してテキストを残す</li>
     *   <li>リンク（[text](url)）を text のみに変換</li>
     *   <li>絵文字・記号文字（Unicode So カテゴリ）を除去</li>
     *   <li>サロゲートペア文字（Cs カテゴリ）を除去</li>
     *   <li>未割当文字（Cn カテゴリ）を除去</li>
     *   <li>制御文字（タブ・改行・復帰以外）を除去</li>
     * </ul>
     *
     * @param text 入力テキスト
     * @return サニタイズ済みテキスト
     */
    static String sanitize(String text) {
        return text
            // コードブロック（```...```）をブロックごと除去
            .replaceAll("```[\\s\\S]*?```", "")
            // 表（| で始まる行）を行ごと除去
            .replaceAll("(?m)^\\|.*$", "")
            // 水平線（--- または *** のみの行）を行ごと除去
            .replaceAll("(?m)^[ \\t]*(-{3,}|\\*{3,})[ \\t]*$", "")
            // 見出し（# 〜 ######）の記号を除去してテキストを残す
            .replaceAll("(?m)^#{1,6}\\s+", "")
            // 引用（> ）の記号を除去してテキストを残す
            .replaceAll("(?m)^>+\\s?", "")
            // 箇条書き（* item）の * を - に置換（行頭のスペース + * + スペース）
            .replaceAll("(?m)^([ \\t]*)\\*\\s", "$1- ")
            // 太字（**text** または __text__）の記号を除去してテキストを残す
            .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
            .replaceAll("__(.+?)__", "$1")
            // 斜体（*text* または _text_）の記号を除去してテキストを残す
            .replaceAll("\\*(.+?)\\*", "$1")
            .replaceAll("_(.+?)_", "$1")
            // インラインコード（`code`）のバッククォートを除去してテキストを残す
            .replaceAll("`(.+?)`", "$1")
            // リンク（[text](url)）を text のみに変換
            .replaceAll("\\[(.+?)\\]\\(.*?\\)", "$1")
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
