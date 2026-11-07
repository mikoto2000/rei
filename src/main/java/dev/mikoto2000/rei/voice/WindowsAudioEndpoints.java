package dev.mikoto2000.rei.voice;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
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
    if (!System.getProperty("os.name", "").startsWith("Windows") || Native.POINTER_SIZE != 8)
      throw new IllegalStateException("Windows x64 endpoint monitoring is required");
    if (!WindowsAudioEndpoints.class.getModule().isNativeAccessEnabled())
      throw new IllegalStateException(
          "Endpoint monitoring requires --enable-native-access=ALL-UNNAMED");
    var library = Path.of(System.getenv("SystemRoot"), "System32", "Ole32.dll");
    // JNA manages the shared DLL handle. The COM references and buffers are per query.
    var api = new Com(NativeLibrary.getInstance(library.toString()));
    api.check(api.function("CoInitializeEx").invokeInt(new Object[] {null, 0}));
    try {
      return api.endpoints();
    } finally {
      api.function("CoUninitialize").invokeVoid(new Object[0]);
    }
  }

  /** Read-only access to the MMDevice interfaces and their Windows x64 layouts. */
  private record Com(NativeLibrary library) {
    Function function(String name) {
      return library.getFunction(name, Function.ALT_CONVENTION);
    }

    void check(int result) {
      if (result < 0)
        throw new IllegalStateException(
            "Windows endpoint API failed (HRESULT " + Integer.toHexString(result) + ")");
    }

    int invoke(Pointer object, int slot, Object... args) {
      var table = requirePointer(object).getPointer(0);
      var address = requirePointer(table).getPointer((long) slot * Native.POINTER_SIZE);
      var parameters = new Object[args.length + 1];
      parameters[0] = object;
      System.arraycopy(args, 0, parameters, 1, args.length);
      return Function.getFunction(requirePointer(address), Function.ALT_CONVENTION)
          .invokeInt(parameters);
    }

    Pointer requirePointer(Pointer pointer) {
      if (pointer == null) throw new IllegalStateException("Windows endpoint pointer is missing");
      return pointer;
    }

    void release(Pointer object) {
      if (object != null) invoke(object, 2);
    }

    void guid(Memory memory, String text) {
      var value = UUID.fromString(text);
      long most = value.getMostSignificantBits(), least = value.getLeastSignificantBits();
      memory.setInt(0, (int) (most >>> 32));
      memory.setShort(4, (short) (most >>> 16));
      memory.setShort(6, (short) most);
      for (int i = 0; i < 8; i++) memory.setByte(8 + i, (byte) (least >>> (56 - i * 8)));
    }

    String wideString(Pointer pointer) {
      requirePointer(pointer);
      var value = new StringBuilder();
      for (int i = 0; i < 512; i++) {
        char c = (char) pointer.getShort((long) i * Character.BYTES);
        if (c == 0) return value.toString();
        value.append(c);
      }
      throw new IllegalStateException("Windows endpoint string exceeds the supported bound");
    }

    List<Endpoint> endpoints() {
      try (var output = new Memory(Native.POINTER_SIZE);
          var classId = new Memory(16);
          var interfaceId = new Memory(16);
          var count = new Memory(Integer.BYTES)) {
        guid(classId, "bcde0395-e52f-467c-8e3d-c4579291692e");
        guid(interfaceId, "a95664d2-9614-4f35-a746-de8db63617e6");
        Pointer enumerator = null, collection = null;
        try {
          output.clear();
          check(function("CoCreateInstance")
              .invokeInt(new Object[] {classId, null, 1, interfaceId, output}));
          enumerator = output.getPointer(0);
          output.clear();
          check(invoke(enumerator, 3, 1, 15, output));
          collection = output.getPointer(0);
          count.clear();
          check(invoke(collection, 3, count));
          int size = count.getInt(0);
          if (size < 0 || size > 256)
            throw new IllegalStateException("Windows endpoint count exceeds the supported bound");
          var endpoints = new ArrayList<Endpoint>();
          for (int i = 0; i < size; i++) {
            output.clear();
            check(invoke(collection, 4, i, output));
            var device = output.getPointer(0);
            try {
              endpoints.add(endpoint(device));
            } finally {
              release(device);
            }
          }
          return List.copyOf(endpoints);
        } finally {
          try {
            release(collection);
          } finally {
            release(enumerator);
          }
        }
      }
    }

    Endpoint endpoint(Pointer device) {
      try (var output = new Memory(Native.POINTER_SIZE);
          var state = new Memory(Integer.BYTES);
          var key = new Memory(20);
          var value = new Memory(24)) {
        state.clear();
        output.clear();
        check(invoke(device, 6, state));
        check(invoke(device, 5, output));
        var idPointer = output.getPointer(0);
        String id;
        try {
          id = wideString(idPointer);
        } finally {
          function("CoTaskMemFree").invokeVoid(new Object[] {idPointer});
        }
        output.clear();
        check(invoke(device, 4, 0, output));
        var properties = output.getPointer(0);
        try {
          guid(key, "a45c254e-df1c-4efd-8020-67d146a850e0");
          key.setInt(16, 14);
          value.clear();
          try {
            check(invoke(properties, 5, key, value));
            if (value.getShort(0) != 31)
              throw new IllegalStateException(
                  "Windows endpoint friendly name has an unsupported type");
            return new Endpoint(id, wideString(value.getPointer(8)), state.getInt(0));
          } finally {
            check(function("PropVariantClear").invokeInt(new Object[] {value}));
          }
        } finally {
          release(properties);
        }
      }
    }
  }

  /** 音声による対話の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) {
    new WindowsAudioEndpoints().captureEndpoints().forEach(System.out::println);
  }
}
