package pt.tecnico.ulisboa.hds.client;

import pt.tecnico.ulisboa.hds.client.api.ClientFrontend;
import pt.tecnico.ulisboa.hds.lib.crypto.Crypto;

import javax.crypto.SecretKey;
import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;

public class ClientApp {

  public static void main(String[] args) {
    System.out.println(ClientApp.class.getSimpleName());

    // Print Arguments
    System.out.printf("Received %d Argument(s)%n", args.length);
    for (int i = 0; i < args.length; i++) {
      System.out.printf("Arg[%d] = %s%n", i, args[i]);
    }

    // Check Arguments
    if (args.length != 5) {
      System.err.println("Invalid Number Of Arguments");
      System.err.println(
          "Arguments: Server Host, Server Port, Server Public Key, Client Private Key, AES Key");
      return;
    }

    String sHost = args[0];
    int sPort = parsePort(args[1]);
    PublicKey sPubKey = Crypto.loadPubKey(new File(args[2]));
    PrivateKey cPrivKey = Crypto.loadPrivKey(new File(args[3]));
    SecretKey aesKey = Crypto.loadAESKey(new File(args[4]));

    ClientFrontend frontend = new ClientFrontend(sHost, sPort, sPubKey, cPrivKey, aesKey);
    CommandReader commandReader = new CommandReader(frontend);

    commandReader.run();
    frontend.shutdown();
  }

  private static int parsePort(String arg) {
    int p = -1;
    try {
      p = Integer.parseInt(arg);
    } catch (NumberFormatException ignored) {
    }
    if (p < 0 || p > 65535) {
      System.err.printf("Invalid Argument '%s'!%n", arg);
      System.exit(1);
    }
    return p;
  }
}
