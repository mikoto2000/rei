package dev.mikoto2000.rei;

import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** れいアプリケーションの結果と状態の契約を検証するテスト。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
@org.junit.jupiter.api.Tag("integration")
class ReiApplicationTests {

  /** 検証に使う応答生成に使うモデル。 */
  @MockitoBean ChatModel chatModel;

  /** 検証に使う埋め込み表現モデル。 */
  @MockitoBean EmbeddingModel embeddingModel;

  /** 検証に使うベクトル保存先。 */
  @MockitoBean VectorStore vectorStore;

  /** 検証に使うベクトル文書保存と検索の窓口。 */
  @MockitoBean VectorDocumentRepository vectorDocumentRepository;

  /** 検証に使うアプリケーション文脈。 */
  @Autowired ApplicationContext applicationContext;

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void contextLoads() {}

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void passwordAuthenticationUserIsNotCreated() {
    org.assertj.core.api.Assertions.assertThat(
            applicationContext.getBeansOfType(UserDetailsService.class))
        .isEmpty();
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void chatCommandIsSpringBeanForPicocliFactory() {
    applicationContext.getBean(ChatCommand.class);
  }
}
