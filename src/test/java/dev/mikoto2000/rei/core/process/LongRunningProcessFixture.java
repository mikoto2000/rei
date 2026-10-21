package dev.mikoto2000.rei.core.process;

/** 検証条件を再現するための長い実行中プロセス検証条件。 */
public class LongRunningProcessFixture {

  /** 検証用に、外部プロセスの管理の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("exit")) {
      System.out.println("fixture stdout");
      System.err.println("fixture stderr");
      System.exit(Integer.parseInt(args[1]));
    }

    System.out.println("ready");
    System.err.println("err-ready");
    System.out.flush();
    System.err.flush();
    while (true) {
      Thread.sleep(1000);
    }
  }
}
