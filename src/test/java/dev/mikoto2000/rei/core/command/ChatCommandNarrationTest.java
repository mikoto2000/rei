package dev.mikoto2000.rei.core.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class ChatCommandNarrationTest {

    @Test
    void runCallsResetOnNarratorAtStart() {
        ChatClient chatClient = Mockito.mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
        ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
        CommandCancellationService cancellationService = new CommandCancellationService();
        ChatResponseNarrator chatResponseNarrator = Mockito.mock(ChatResponseNarrator.class);

        when(modelHolderService.get()).thenReturn("gpt-test");
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.stream().content()).thenReturn(Flux.just("answer ", "text"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, chatResponseNarrator))
                    .execute("hello");
        } finally {
            System.setOut(originalOut);
        }

        verify(chatResponseNarrator).reset();
    }

    @Test
    void runCallsNarrateIfCompletedWithFullResponseTextOnSuccess() {
        ChatClient chatClient = Mockito.mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
        ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
        CommandCancellationService cancellationService = new CommandCancellationService();
        ChatResponseNarrator chatResponseNarrator = Mockito.mock(ChatResponseNarrator.class);

        when(modelHolderService.get()).thenReturn("gpt-test");
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.stream().content()).thenReturn(Flux.just("answer ", "text"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, chatResponseNarrator))
                    .execute("hello");
        } finally {
            System.setOut(originalOut);
        }

        verify(chatResponseNarrator).narrateIfCompleted("answer text");
    }
}
