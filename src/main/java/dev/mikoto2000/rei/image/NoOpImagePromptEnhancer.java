package dev.mikoto2000.rei.image;

class NoOpImagePromptEnhancer implements ImagePromptEnhancer {

  @Override
  public String enhance(String userRequest) {
    return userRequest;
  }
}
