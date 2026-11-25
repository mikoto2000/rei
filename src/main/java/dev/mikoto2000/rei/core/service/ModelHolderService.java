package dev.mikoto2000.rei.core.service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** モデル保持先のユースケースを実現する。 */
@Service
public class ModelHolderService {
  /** 現在モデル。 */
  private final AtomicReference<String> currentModel;

  private UnaryOperator<String> chatModelResolver = UnaryOperator.identity();

  @Autowired(required = false)
  void setChatModelResolver(@Qualifier("chatModelResolver") UnaryOperator<String> resolver) {
    chatModelResolver = resolver;
  }

  /** 指定された値や利用先を使って、ModelHolderServiceを初期化する。 */
  public ModelHolderService(@Value("${spring.ai.openai.chat.options.model}") String defaultModel) {
    this.currentModel = new AtomicReference<>(defaultModel);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public String get() {
    return currentModel.get();
  }

  /** 通常チャットに適用するモデル名を返す。 */
  public String getChatModel() {
    return chatModelResolver.apply(get());
  }

  /** 以後の処理で使う値を、指定された内容へ切り替える。 */
  public void set(String model) {
    this.currentModel.set(model);
  }
}
