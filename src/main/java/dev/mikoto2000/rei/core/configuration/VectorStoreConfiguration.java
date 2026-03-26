package dev.mikoto2000.rei.core.configuration;

import java.io.File;
import java.io.IOException;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfiguration {

    private static final File STORE_FILE = new File("~/.cache/rei/vector-store.json");

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) throws IOException {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        if (STORE_FILE.exists()) {
            store.load(STORE_FILE);
        }

        return store;
    }
}
