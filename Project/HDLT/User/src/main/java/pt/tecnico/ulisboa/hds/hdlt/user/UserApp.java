package pt.tecnico.ulisboa.hds.hdlt.user;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.user.api.*;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.*;

public class UserApp {

  public static void main(String[] args) {
    System.out.println(UserApp.class.getSimpleName());

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
    Map<String, String> uURLs = parseURLs(argsMap.get("UsersURLsPath"), null);
    Map<String, String> sURLs =
        parseURLs(argsMap.get("ServersURLsPath"), 3 * nByzantineServers + 1);
    UserCrypto uCrypto =
        new UserCrypto(
            argsMap.get("UserKSPath"),
            argsMap.get("UserCrtDirPath"),
            argsMap.get("ServerCrtDirPath"),
            ksAlias,
            ksPwd,
            Integer.parseInt(argsMap.get("SessionTime")),
            Integer.parseInt(argsMap.get("PowDifficulty")));
    GridManager grid;
    try {
      grid = new GridManager(uname, argsMap.get("GridPath"));
    } catch (FileNotFoundException e) {
      System.err.printf("Invalid GridPath: %s!%n", argsMap.get("GridPath"));
      return;
    }
    long epochLifeTime = Long.parseLong(argsMap.get("EpochLifeTime"));
    int callTimeout = Integer.parseInt(argsMap.get("myCallTimeout"));
    int maxNRetries = Integer.parseInt(argsMap.get("myMaxNRetries"));

    UserToDHServerFrontend dhFrontend =
        new UserToDHServerFrontend(uname, uCrypto, sURLs, callTimeout, maxNRetries);
    ClientToServerFrontend csFrontend =
        new ClientToServerFrontend(
            uname,
            grid,
            uCrypto,
            sURLs,
            nByzantineServers,
            nByzantineUsers,
            dhFrontend,
            callTimeout,
            maxNRetries);
    UserToUserFrontend uuFrontend =
        new UserToUserFrontend(
            uname, grid, uCrypto, nByzantineUsers, uMaxDistance, uURLs, callTimeout, maxNRetries);
    CommandReader commandReader = new CommandReader(csFrontend, uuFrontend);

    int uPort = parsePort(uURLs.get(uname).split(":")[1]);
    final Server uServer =
        ServerBuilder.forPort(uPort)
            .addService(new UserUserServicesImpl(uname, grid, uCrypto, uMaxDistance))
            .build();

    try {
      uServer.start();
    } catch (IOException e) {
      throw new AssertError(UserApp.class.getSimpleName(), "main", e);
    }
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> shutdown(uServer, dhFrontend, csFrontend, uuFrontend)));

    if (epochLifeTime > 0) submitULReports(uname, grid, commandReader, epochLifeTime);
    commandReader.run();
    shutdown(uServer, dhFrontend, csFrontend, uuFrontend);
  }

  private static void shutdown(
      Server uServer,
      UserToDHServerFrontend dhFrontend,
      ClientToServerFrontend csFrontend,
      UserToUserFrontend uuFrontend) {
    System.out.printf("%nGoodbye!%n");
    uServer.shutdownNow();
    dhFrontend.shutdown();
    csFrontend.shutdown();
    uuFrontend.shutdown();
  }

  private static void submitULReports(
      String uname, GridManager grid, CommandReader commandReader, long epochLifeTime) {
    grid.getEpochs(uname).stream()
        .sorted()
        .forEach(
            epoch -> {
              try {
                System.out.println(commandReader.doSubmitULReport(epoch));
              } catch (UserRuntimeException e) {
                System.out.printf("Error: %s%n", e.getMessage());
              }
              sleep(epochLifeTime);
            });
  }

  private static List<Map.Entry<String, String>> getManual() {
    return new ArrayList<>() {
      {
        add(Map.entry("Username", "User Identifier"));
        add(Map.entry("UserKSPath", "User Java Key Store Path"));
        add(Map.entry("UserCrtDirPath", "User Certificates Dir Path"));
        add(Map.entry("ServerCrtDirPath", "Server Certificates Dir Path"));
        add(Map.entry("NByzantineServers", "Number of Byzantine Servers in the System"));
        add(Map.entry("NByzantineUsers", "Number of Byzantine Users in the System"));
        add(Map.entry("MaxDistance", "Maximum Distance of Closeness"));
        add(Map.entry("GridPath", "Grid Path"));
        add(Map.entry("UsersURLsPath", "Users URLs Path"));
        add(Map.entry("ServersURLsPath", "Servers URLs Path"));
        add(Map.entry("SessionTime", "Session Duration in Seconds"));
        add(Map.entry("EpochLifeTime", "Epoch Life Duration in Milliseconds"));
        add(Map.entry("PowDifficulty", "Number of Leading Zeros of Pow"));
        add(Map.entry("KeyStoreAlias", "Java Key Store Alias"));
        add(Map.entry("KeyStorePwd", "Java Key Store Password"));
        add(Map.entry("myCallTimeout", "Frontend Call Timeout"));
        add(Map.entry("myMaxNRetries", "Frontend Maximum Number of Retries"));
      }
    };
  }
}
