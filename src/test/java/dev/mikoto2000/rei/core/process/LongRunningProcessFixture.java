package dev.mikoto2000.rei.core.process;

public class LongRunningProcessFixture {

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
