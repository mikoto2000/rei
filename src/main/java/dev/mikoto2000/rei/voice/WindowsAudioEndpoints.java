package dev.mikoto2000.rei.voice;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 音声による対話に必要なWindows音声接続先一覧を扱う。
 *
 * <p>Read-only MMDevice inventory. Native resources belong to one query and are released on every
 * path.
 */
public final class WindowsAudioEndpoints {
  /**
   * 音声による対話で受け渡す接続先を表す。
   *
   * @param id 識別子
   * @param name 名前
   * @param state 状態
   */
  public record Endpoint(String id, String name, int state) {}

  /** 後から状況を確認できるよう、接続先一覧を記録する。 */
  public List<Endpoint> captureEndpoints() {
    if (!System.getProperty("os.name", "").startsWith("Windows") || ADDRESS.byteSize() != 8)
      throw new IllegalStateException("Windows x64 endpoint monitoring is required");
    if (!WindowsAudioEndpoints.class.getModule().isNativeAccessEnabled())
      throw new IllegalStateException(
          "Endpoint monitoring requires --enable-native-access=ALL-UNNAMED");
    try (var arena = Arena.ofConfined()) {
      var linker = Linker.nativeLinker();
      var library =
          SymbolLookup.libraryLookup(
              Path.of(System.getenv("SystemRoot"), "System32", "Ole32.dll"), arena);
      var api = new Com(arena, linker, library);
      api.check(
          api.call(
              api.function("CoInitializeEx", JAVA_INT, ADDRESS, JAVA_INT), MemorySegment.NULL, 0));
      try {
        return api.endpoints();
      } finally {
        api.call(api.function("CoUninitialize", null));
      }
    }
  }

  /** 音声による対話に必要なComを扱う。 */
  private static final class Com {
    /** OS連携で使う資源の管理範囲。 */
    final Arena arena;

    /** OSの機能を呼び出す窓口。 */
    final Linker linker;

    /** 保管資料。 */
    final SymbolLookup library;

    /** 指定された値や利用先を使って、Comを初期化する。 */
    Com(Arena arena, Linker linker, SymbolLookup library) {
      this.arena = arena;
      this.linker = linker;
      this.library = library;
    }

    /** 音声による対話で用いる変換処理を返す。 */
    MethodHandle function(String name, MemoryLayout result, MemoryLayout... args) {
      return linker.downcallHandle(library.find(name).orElseThrow(), descriptor(result, args));
    }

    /** 音声による対話で用いるdescriptorを返す。 */
    FunctionDescriptor descriptor(MemoryLayout result, MemoryLayout... args) {
      return result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
    }

    /** 音声による対話の要求を実行し、呼び出し元へ結果を返す。 */
    Object call(MethodHandle handle, Object... args) {
      try {
        return handle.invokeWithArguments(args);
      } catch (Throwable failure) {
        throw new IllegalStateException("Windows endpoint query failed", failure);
      }
    }

    /** 後続の処理を進められるか、現在の条件を確認する。 */
    void check(Object result) {
      if ((Integer) result < 0)
        throw new IllegalStateException(
            "Windows endpoint API failed (HRESULT " + Integer.toHexString((Integer) result) + ")");
    }

    /** 音声による対話で用いるメソッドを返す。 */
    MethodHandle method(MemorySegment object, int slot, MemoryLayout... args) {
      var table = object.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
      var address = table.reinterpret((slot + 1) * ADDRESS.byteSize()).getAtIndex(ADDRESS, slot);
      var layouts = new MemoryLayout[args.length + 1];
      layouts[0] = ADDRESS;
      System.arraycopy(args, 0, layouts, 1, args.length);
      return linker.downcallHandle(address, FunctionDescriptor.of(JAVA_INT, layouts));
    }

    /** 後続の処理が利用できるよう、Comを解放する。 */
    void release(MemorySegment object) {
      if (!object.equals(MemorySegment.NULL)) call(method(object, 2), object);
    }

    /** 音声による対話で用いるguidを返す。 */
    MemorySegment guid(String text) {
      var value = UUID.fromString(text);
      var segment = arena.allocate(16, 4);
      long most = value.getMostSignificantBits(), least = value.getLeastSignificantBits();
      segment.set(JAVA_INT, 0, (int) (most >>> 32));
      segment.set(JAVA_SHORT, 4, (short) (most >>> 16));
      segment.set(JAVA_SHORT, 6, (short) most);
      for (int i = 0; i < 8; i++) segment.set(JAVA_BYTE, 8 + i, (byte) (least >>> (56 - i * 8)));
      return segment;
    }

    /** 音声による対話で用いるwide文字列表現を返す。 */
    String wideString(MemorySegment pointer) {
      if (pointer.equals(MemorySegment.NULL))
        throw new IllegalStateException("Windows endpoint string is missing");
      var chars = pointer.reinterpret(1024);
      var value = new StringBuilder();
      for (int i = 0; i < 512; i++) {
        char c = chars.getAtIndex(JAVA_CHAR, i);
        if (c == 0) return value.toString();
        value.append(c);
      }
      throw new IllegalStateException("Windows endpoint string exceeds the supported bound");
    }

    /** 音声による対話で用いる接続先一覧を返す。 */
    List<Endpoint> endpoints() {
      var output = arena.allocate(ADDRESS);
      MemorySegment enumerator = MemorySegment.NULL, collection = MemorySegment.NULL;
      try {
        check(
            call(
                function(
                    "CoCreateInstance", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                guid("bcde0395-e52f-467c-8e3d-c4579291692e"),
                MemorySegment.NULL,
                1,
                guid("a95664d2-9614-4f35-a746-de8db63617e6"),
                output));
        enumerator = output.get(ADDRESS, 0);
        output.set(ADDRESS, 0, MemorySegment.NULL);
        check(call(method(enumerator, 3, JAVA_INT, JAVA_INT, ADDRESS), enumerator, 1, 15, output));
        collection = output.get(ADDRESS, 0);
        var count = arena.allocate(JAVA_INT);
        check(call(method(collection, 3, ADDRESS), collection, count));
        int size = count.get(JAVA_INT, 0);
        if (size < 0 || size > 256)
          throw new IllegalStateException("Windows endpoint count exceeds the supported bound");
        var endpoints = new ArrayList<Endpoint>();
        for (int i = 0; i < size; i++) {
          output.set(ADDRESS, 0, MemorySegment.NULL);
          check(call(method(collection, 4, JAVA_INT, ADDRESS), collection, i, output));
          var device = output.get(ADDRESS, 0);
          try {
            endpoints.add(endpoint(device));
          } finally {
            release(device);
          }
        }
        return List.copyOf(endpoints);
      } finally {
        release(collection);
        release(enumerator);
      }
    }

    /** 音声による対話で用いる接続先を返す。 */
    Endpoint endpoint(MemorySegment device) {
      var output = arena.allocate(ADDRESS);
      var state = arena.allocate(JAVA_INT);
      check(call(method(device, 6, ADDRESS), device, state));
      check(call(method(device, 5, ADDRESS), device, output));
      var idPointer = output.get(ADDRESS, 0);
      String id;
      try {
        id = wideString(idPointer);
      } finally {
        call(function("CoTaskMemFree", null, ADDRESS), idPointer);
      }
      output.set(ADDRESS, 0, MemorySegment.NULL);
      check(call(method(device, 4, JAVA_INT, ADDRESS), device, 0, output));
      var properties = output.get(ADDRESS, 0);
      try {
        var key = arena.allocate(20, 4);
        MemorySegment.copy(guid("a45c254e-df1c-4efd-8020-67d146a850e0"), 0, key, 0, 16);
        key.set(JAVA_INT, 16, 14);
        var value = arena.allocate(24, 8);
        try {
          check(call(method(properties, 5, ADDRESS, ADDRESS), properties, key, value));
          if (value.get(JAVA_SHORT, 0) != 31)
            throw new IllegalStateException(
                "Windows endpoint friendly name has an unsupported type");
          return new Endpoint(id, wideString(value.get(ADDRESS, 8)), state.get(JAVA_INT, 0));
        } finally {
          check(call(function("PropVariantClear", JAVA_INT, ADDRESS), value));
        }
      } finally {
        release(properties);
      }
    }
  }

  /** 音声による対話の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) {
    new WindowsAudioEndpoints().captureEndpoints().forEach(System.out::println);
  }
}
