package pt.tecnico.ulisboa.hds.hdlt.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ClientServerServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBInstanceManager;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ExceptionHandler;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.DBManager;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsCleanupThread;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parsePort;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parseURLs;

public class ServerApp {

  public static void main(String[] args) {
    System.out.println(ServerApp.class.getSimpleName());

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

    String sName = argsMap.get("ServerName");
    String ksAlias = String.format("%s%s", argsMap.get("KeyStoreAlias"), sName);
    String ksPwd = String.format("%s%s", argsMap.get("KeyStorePwd"), sName);
    int nByzantineServers = Integer.parseInt(argsMap.get("NByzantineServers"));
    int nByzantineUsers = Integer.parseInt(argsMap.get("NByzantineUsers"));
    int expSec = Integer.parseInt(argsMap.get("SessionTime"));
    Map<String, String> sURLs =
        parseURLs(argsMap.get("ServersURLsPath"), 3 * nByzantineServers + 1);
    SessionsManager sessionsManager = new SessionsManager(expSec);
    ServerCrypto sCrypto =
        new ServerCrypto(
            argsMap.get("ServerKSPath"),
            argsMap.get("ServerCrtDirPath"),
            argsMap.get("UserCrtDirPath"),
            argsMap.get("HACrtPath"),
            ksAlias,
            ksPwd,
            sessionsManager,
            Integer.parseInt(argsMap.get("PowDifficulty")));
    DBManager db =
        new DBManager(argsMap.get("DBName"), argsMap.get("DBUser"), argsMap.get("DBPwd"), sCrypto);
    int callTimeout = Integer.parseInt(argsMap.get("myCallTimeout"));
    int maxNRetries = Integer.parseInt(argsMap.get("myMaxNRetries"));

    ADEBInstanceManager adebInstanceManager = new ADEBInstanceManager(sCrypto, db);
    DHFrontend dhFrontend = new DHFrontend(sName, sCrypto, sURLs, callTimeout, maxNRetries);
    ADEBFrontend adebFrontend =
        new ADEBFrontend(sName, sCrypto, sURLs, dhFrontend, callTimeout, maxNRetries);
    SessionsCleanupThread sessionsCleanupInstance = new SessionsCleanupThread(sessionsManager);

    Thread sessionsCleanup = new Thread(sessionsCleanupInstance);
    sessionsCleanup.start();

    int sPort = parsePort(sURLs.get(sName).split(":")[1]);
    final Server server =
        ServerBuilder.forPort(sPort)
            .addService(new DHServicesImpl(sCrypto, db))
            .addService(
                new ADEBServicesImpl(
                    sName,
                    sCrypto,
                    adebInstanceManager,
                    adebFrontend,
                    nByzantineUsers,
                    nByzantineServers))
            .addService(
                new ClientServerServicesImpl(
                    sCrypto,
                    db,
                    adebInstanceManager,
                    adebFrontend,
                    nByzantineUsers,
                    nByzantineServers))
            .intercept(new ExceptionHandler())
            .build();

    try {
      server.start();
    } catch (IOException e) {
      throw new AssertError(ServerApp.class.getSimpleName(), "main", e);
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.printf("%nGoodbye!%n");
                  dhFrontend.shutdown();
                  adebFrontend.shutdown();
                  sessionsCleanupInstance.stop(sessionsCleanup);
                  server.shutdownNow();
                }));

    // Wait Until Server is Terminated
    try {
      server.awaitTermination();
    } catch (InterruptedException e) {
      throw new AssertError(ServerApp.class.getSimpleName(), "main", e);
    }
  }

  private static List<Map.Entry<String, String>> getManual() {
    return new ArrayList<>() {
      {
        add(Map.entry("ServerName", "Server Identifier"));
        add(Map.entry("ServerPort", "Server Port"));
        add(Map.entry("DBName", "Server Database Name"));
        add(Map.entry("DBUser", "Server Database User"));
        add(Map.entry("DBPwd", "Server Database Password"));
        add(Map.entry("ServerKSPath", "Server Java Key Store Path"));
        add(Map.entry("ServerCrtDirPath", "Server Certificates Dir Path"));
        add(Map.entry("UserCrtDirPath", "User Certificates Dir Path"));
        add(Map.entry("HACrtPath", "HA Certificate Path"));
        add(Map.entry("NByzantineServers", "Number of Byzantine Servers in the System"));
        add(Map.entry("NByzantineUsers", "Number of Byzantine Users in the System"));
        add(Map.entry("SessionTime", "Session Duration in Seconds"));
        add(Map.entry("PowDifficulty", "Number of Leading Zeros of Pow"));
        add(Map.entry("ServersURLsPath", "Servers URLs Path"));
        add(Map.entry("KeyStoreAlias", "Java Key Store Alias"));
        add(Map.entry("KeyStorePwd", "Java Key Store Password"));
        add(Map.entry("myCallTimeout", "Frontend Call Timeout"));
        add(Map.entry("myMaxNRetries", "Frontend Maximum Number of Retries"));
      }
    };
  }
}
