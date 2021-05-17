package pt.tecnico.ulisboa.hds.hdlt.lib.common;

import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

public class Common {

  public static int parsePort(String arg) {
    int p = -1;
    try {
      p = Integer.parseInt(arg);
    } catch (NumberFormatException ignored) {
    }
    if (p < 0 || p > 65535) {
      throw new AssertError(Common.class.getSimpleName(), "parsePort");
    }
    return p;
  }

  public static Map<String, String> parseURLs(String URLsPath, Integer nURLs) {
    Map<String, String> URLs = new HashMap<>();
    try (Scanner scanner = new Scanner(new File(URLsPath))) {
      while (scanner.hasNextLine() && (nURLs == null || nURLs-- > 0)) {
        String[] URL = scanner.nextLine().split(";");
        if (URL.length < 2) {
          System.err.printf("Invalid Argument '%s'!%n", URLsPath);
        }
        URLs.put(URL[0].trim(), URL[1].trim());
      }
    } catch (FileNotFoundException e) {
      throw new AssertError(Common.class.getSimpleName(), "parseURLs");
    }
    return URLs;
  }

  public static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
    }
  }

  public static void await(CountDownLatch countDownLatch) {
    try {
      countDownLatch.await();
    } catch (InterruptedException ignored) {
    }
  }
}
