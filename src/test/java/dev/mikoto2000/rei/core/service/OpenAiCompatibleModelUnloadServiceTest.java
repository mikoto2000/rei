package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelUnloadServiceTest {

  @Test
  void generateUriUsesApiGenerateWhenV1Missing() {
    assertEquals(URI.create("https://api.example.com/api/generate"),
        OpenAiCompatibleModelUnloadService.generateUri("https://api.example.com"));
  }

  @Test
  void generateUriStripsV1Suffix() {
    assertEquals(URI.create("https://api.example.com/api/generate"),
        OpenAiCompatibleModelUnloadService.generateUri("https://api.example.com/v1"));
  }
}
