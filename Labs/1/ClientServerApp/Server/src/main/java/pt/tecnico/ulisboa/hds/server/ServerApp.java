package pt.tecnico.ulisboa.hds.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import pt.tecnico.ulisboa.hds.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.server.api.ServerServicesImpl;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.PublicKey;

public class ServerApp {

  public static void main(String[] args) {
    System.out.println(ServerApp.class.getSimpleName());

    // Print Arguments
    System.out.printf("Received %d Argument(s)%n", args.length);
    for (int i = 0; i < args.length; i++) {
      System.out.printf("Arg[%d] = %s%n", i, args[i]);
    }

    // Check Arguments
    if (args.length != 4) {
      System.err.println("Invalid Number Of Arguments");
      System.err.println("Arguments: Port, Client Public Key, Server Private Key, AES Key");
      return;
    }
    int sPort = parsePort(args[0]);
    PublicKey cPubKey = Crypto.loadPubKey(new File(args[1]));
    PrivateKey sPrivKey = Crypto.loadPrivKey(new File(args[2]));
    SecretKey aesKey = Crypto.loadAESKey(new File(args[3]));

    // Start Server
    final Server server =
        ServerBuilder.forPort(sPort)
            .addService(new ServerServicesImpl(cPubKey, sPrivKey, aesKey))
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
                  System.out.println(String.format("%nGoodbye!"));
                  server.shutdownNow();
                }));

    // Wait Until Server is Terminated
    try {
      server.awaitTermination();
    } catch (InterruptedException e) {
      throw new AssertError(ServerApp.class.getSimpleName(), "main", e);
    }
  }

  private static int parsePort(String arg) {
    int p = -1;
    try {
      p = Integer.parseInt(arg);
    } catch (NumberFormatException ignored) {
    }
    if (p < 0 || p > 65535) {
      throw new AssertError(String.format("Invalid Argument '%s'!%n", arg));
    }
    return p;
  }
}
