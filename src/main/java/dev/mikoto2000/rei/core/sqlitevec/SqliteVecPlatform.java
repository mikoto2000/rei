package dev.mikoto2000.rei.core.sqlitevec;

public enum SqliteVecPlatform {
  LINUX_X86_64("linux-x86_64", "vec0.so"),
  LINUX_AARCH64("linux-aarch64", "vec0.so"),
  MACOS_X86_64("macos-x86_64", "vec0.dylib"),
  MACOS_AARCH64("macos-aarch64", "vec0.dylib"),
  WINDOWS_X86_64("windows-x86_64", "vec0.dll");

  private final String id;
  private final String libraryFileName;

  SqliteVecPlatform(String id, String libraryFileName) {
    this.id = id;
    this.libraryFileName = libraryFileName;
  }

  public String id() {
    return id;
  }

  public String libraryFileName() {
    return libraryFileName;
  }
}
