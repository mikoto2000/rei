package dev.mikoto2000.rei.voice;
import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;import java.nio.charset.*;import java.nio.*;import java.io.*;import java.util.*;import javax.sound.sampled.*;
/** Offline synthetic/public fixtures only: inspect token/text boundary, never microphone or Agent. */
public class VoiceTokenDiagnostic {
 public static void main(String[] args)throws Exception{
  if(args.length!=3&&args.length!=4)throw new IllegalArgumentException("MODELS PROFILE WAV");
  Path models=Path.of(args[0]);String name=args[1];if(!Set.of("turbo","base","small").contains(name))throw new IllegalArgumentException("FP32 profile only");
  float[] samples;try(var source=AudioSystem.getAudioInputStream(Path.of(args[2]).toFile());var pcm=AudioFormatConverter.toPcm16(source)){samples=VoicePcm.decode(pcm.readAllBytes());}
  if(samples.length>448000)throw new IllegalArgumentException("Audio too long");
  boolean safe=args.length==4&&args[3].equals("--byte-safe");
  if(args.length==4&&!safe)throw new IllegalArgumentException("Unknown diagnostic flag");
  try(var vocabulary=safe?WhisperByteVocabulary.create(models.resolve(name+"-tokens.txt")):null) {
  Path tokens=safe?vocabulary.path():models.resolve(name+"-tokens.txt");
  var whisper=OfflineWhisperModelConfig.builder().setEncoder(models.resolve(name+"-encoder.onnx").toString()).setDecoder(models.resolve(name+"-decoder.onnx").toString()).setLanguage("ja").setTask("transcribe").setTailPaddings(1000).build();
  var recognizer=new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder().setWhisper(whisper).setTokens(tokens.toString()).setNumThreads(4).setProvider("cpu").setDebug(false).build()).setDecodingMethod("greedy_search").build());
  var stream=recognizer.createStream();try{stream.acceptWaveform(samples,16000);recognizer.decode(stream);var result=recognizer.getResult(stream);System.out.println("PROFILE="+name+" LANGUAGE="+result.getLang());String text=result.getText();
  if(safe)text=WhisperByteVocabulary.decode(text);
  System.out.println("BYTE_SAFE="+safe+" TEXT="+text);if(!safe)System.out.println("TOKENS="+Arrays.toString(result.getTokens()));}finally{stream.release();recognizer.release();}
  }
 }
}
