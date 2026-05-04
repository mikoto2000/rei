package dev.mikoto2000.rei;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class ReiApplicationTests {

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	EmbeddingModel embeddingModel;

	@MockitoBean
	VectorStore vectorStore;

	@MockitoBean
	VectorDocumentRepository vectorDocumentRepository;

	@Test
	void contextLoads() {
	}

}
