package dev.mikoto2000.rei.voice;

import java.util.*;
import java.util.function.Supplier;

/** Pins a Java Sound selection to one active Windows endpoint; never follows the default. */
public final class WindowsMicrophoneMonitor {
  public record Binding(String endpointId, String name) {}
  private final Supplier<List<WindowsAudioEndpoints.Endpoint>> inventory;
  private final Map<String,String> selectedEndpoints=new HashMap<>();
  public WindowsMicrophoneMonitor(Supplier<List<WindowsAudioEndpoints.Endpoint>> inventory) {
    this.inventory=Objects.requireNonNull(inventory);
  }
  public synchronized Binding bind(AudioDevice device) {
    var matches=inventory.get().stream().filter(e->e.state()==1&&e.name().equals(device.name())).toList();
    if(matches.size()!=1)throw new IllegalStateException("Microphone endpoint is missing or ambiguous; select it explicitly");
    var endpoint=matches.getFirst();var previous=selectedEndpoints.get(device.id());
    if(previous!=null&&!previous.equals(endpoint.id()))throw new IllegalStateException("Microphone endpoint changed; explicit reselection is required");
    selectedEndpoints.put(device.id(),endpoint.id());return new Binding(endpoint.id(),endpoint.name());
  }
  public void check(Binding binding) {
    var matches=inventory.get().stream().filter(e->e.id().equals(binding.endpointId())).toList();
    if(matches.size()!=1||matches.getFirst().state()!=1||!matches.getFirst().name().equals(binding.name()))
      throw new IllegalStateException("Selected microphone endpoint is no longer active");
  }
  public synchronized void reselect(String deviceId){selectedEndpoints.remove(deviceId);}
}
