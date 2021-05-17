package pt.tecnico.ulisboa.hds.hdlt.ha;

import pt.tecnico.ulisboa.hds.hdlt.ha.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.ha.api.HACrypto;
import pt.tecnico.ulisboa.hds.hdlt.ha.api.HAToDHServerFrontend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parseURLs;

public class HAApp {

  public static void main(String[] args) {
    System.out.println(HAApp.class.getSimpleName());

    List<Map.Entry<String, String>> manual = getManual();
    if (args.length != manual.size()) {
      System.err.println("Invalid Number Of Arguments! Usage:");
      manual.forEach(e -> System.err.printf("- %s: %s%n", e.getKey(), e.getValue()));
      return;
    }

    Map<String, String> argsMap = new HashMap<>();
    System.out.printf("Received %d Argument(s)%n", args.length);
    for (int i = 0; i < args.length; i++) {
      argsMap.put(manual.get(i).getKey(), args[i]);
      System.out.printf("[%s] = %s%n", manual.get(i).getKey(), args[i]);
    }

    String uname = argsMap.get("Username");
    String ksAlias = String.format("%s%s", argsMap.get("KeyStoreAlias"), uname);
    String ksPwd = String.format("%s%s", argsMap.get("KeyStorePwd"), uname);
    int nByzantineServers = Integer.parseInt(argsMap.get("NByzantineServers"));
    int nByzantineUsers = Integer.parseInt(argsMap.get("NByzantineUsers"));
    int uMaxDistance = Integer.parseInt(argsMap.get("MaxDistance"));
    Map<String, String> sURLs =
        parseURLs(argsMap.get("ServersURLsPath"), 3 * nByzantineServers + 1);
    HACrypto crypto =
        new HACrypto(
            argsMap.get("HAKSPath"),
            argsMap.get("UserCrtDirPath"),
            argsMap.get("ServerCrtDirPath"),
            ksAlias,
            ksPwd,
            Integer.parseInt(argsMap.get("SessionTime")),
            Integer.parseInt(argsMap.get("PowDifficulty")));
    int callTimeout = Integer.parseInt(argsMap.get("myCallTimeout"));
    int maxNRetries = Integer.parseInt(argsMap.get("myMaxNRetries"));

    HAToDHServerFrontend dhFrontend =
        new HAToDHServerFrontend(uname, crypto, sURLs, callTimeout, maxNRetries);
    ClientToServerFrontend csFrontend =
        new ClientToServerFrontend(
            uname,
            crypto,
            sURLs,
            nByzantineServers,
            nByzantineUsers,
            uMaxDistance,
            dhFrontend,
            callTimeout,
            maxNRetries);
    CommandReader commandReader = new CommandReader(csFrontend);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(dhFrontend, csFrontend)));

    commandReader.run();
    shutdown(dhFrontend, csFrontend);
  }

  private static void shutdown(HAToDHServerFrontend dhFrontend, ClientToServerFrontend csFrontend) {
    System.out.printf("%nGoodbye!%n");
    csFrontend.shutdown();
    dhFrontend.shutdown();
  }

  private static List<Map.Entry<String, String>> getManual() {
    return new ArrayList<>() {
      {
        add(Map.entry("Username", "User Identifier"));
        add(Map.entry("HAKSPath", "HA Java Key Store Path"));
        add(Map.entry("UserCrtDirPath", "User Certificates Dir Path"));
        add(Map.entry("ServerCrtDirPath", "Server Certificates Dir Path"));
        add(Map.entry("NByzantineServers", "Number of Byzantine Servers in the System"));
        add(Map.entry("NByzantineUsers", "Number of Byzantine Users in the System"));
        add(Map.entry("MaxDistance", "Maximum Distance of Closeness"));
        add(Map.entry("ServersURLsPath", "Servers URLs Path"));
        add(Map.entry("SessionTime", "Session Duration in Seconds"));
        add(Map.entry("PowDifficulty", "Number of Leading Zeros of Pow"));
        add(Map.entry("KeyStoreAlias", "Java Key Store Alias"));
        add(Map.entry("KeyStorePwd", "Java Key Store Password"));
        add(Map.entry("myCallTimeout", "Frontend Call Timeout"));
        add(Map.entry("myMaxNRetries", "Frontend Maximum Number of Retries"));
      }
    };
  }
}
