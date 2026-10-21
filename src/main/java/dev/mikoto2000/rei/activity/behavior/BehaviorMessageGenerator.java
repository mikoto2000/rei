package dev.mikoto2000.rei.activity.behavior;

/** 作業習慣メッセージの内容を生成する。 */
@FunctionalInterface
public interface BehaviorMessageGenerator {
  /** 指定された条件に従って、依頼された内容を生成する。 */
  String generate(BehaviorNotification notification) throws Exception;
}
