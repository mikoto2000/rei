package dev.mikoto2000.rei.voice;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sound.sampled.*;
public final class AudioDeviceService {
  private final Supplier<List<AudioDevice>> catalog;
  private volatile String selectedId;
  public AudioDeviceService() { this(AudioDeviceService::scan); }
  public AudioDeviceService(Supplier<List<AudioDevice>> catalog) { this.catalog = Objects.requireNonNull(catalog); }
  public void invalidateSelection(){selectedId=null;}
  public void restoreSelection(String id) { selectedId=id; }
  public List<AudioDevice> devices() { return List.copyOf(catalog.get()); }
  public void select(String id) {
    var matches = devices().stream().filter(d -> d.id().equals(id)).toList();
    if (matches.size()!=1) throw new IllegalArgumentException("Missing or ambiguous microphone identity");
    selectedId = id;
  }
  public AudioDevice selected() {
    if (selectedId == null) throw new IllegalStateException("Select a microphone with /voice device set ID");
    var matches = devices().stream().filter(d -> d.id().equals(selectedId)).toList();
    if (matches.size()!=1) throw new IllegalStateException("Selected microphone disconnected or ambiguous; select explicitly");
    return matches.getFirst();
  }
  static AudioDevice describe(Mixer.Info info) {
    String identity = info.getName()+"\u0000"+info.getDescription()+"\u0000"+info.getVendor()+"\u0000"+info.getVersion();
    try {
      String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
      return new AudioDevice(id,info.getName(),info.getDescription(),info.getVendor(),info.getVersion());
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static List<AudioDevice> scan() {
    return Arrays.stream(AudioSystem.getMixerInfo())
        .filter(info -> Arrays.stream(AudioSystem.getMixer(info).getTargetLineInfo())
          .anyMatch(line -> TargetDataLine.class.isAssignableFrom(line.getLineClass())))
        .map(AudioDeviceService::describe).toList();
  }
}