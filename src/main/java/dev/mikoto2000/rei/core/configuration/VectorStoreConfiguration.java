package dev.mikoto2000.rei.core.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfiguration {

    private static final Path STORE_FILE = VectorStorePaths.storeFile();

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) throws IOException {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        if (Files.exists(STORE_FILE)) {
            store.load(STORE_FILE.toFile());
        }

        return store;
    }
}
