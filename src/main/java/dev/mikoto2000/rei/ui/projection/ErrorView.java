package dev.mikoto2000.rei.ui.projection;

import dev.mikoto2000.rei.event.ErrorInformation;

public record ErrorView(String type, String message, String code) {
  static ErrorView from(ErrorInformation error) {
    return error == null ? null : new ErrorView(error.errorType(), error.message(), error.code());
  }
}
