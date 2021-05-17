package pt.tecnico.ulisboa.hds.hdlt.user;

import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

public class CommandReader {

  private static final Scanner scanner = new Scanner(System.in);
  private final ClientToServerFrontend csFrontend;
  private final UserToUserFrontend uuFrontend;

  public CommandReader(ClientToServerFrontend csFrontend, UserToUserFrontend uuFrontend) {
    this.csFrontend = csFrontend;
    this.uuFrontend = uuFrontend;
  }

  public void run() {
    int option = -1;
    do {
      try {
        displayMenu();
        System.out.print(">>> ");
        option = Integer.parseInt(scanner.nextLine());
        System.out.println(parse(option));
      } catch (NumberFormatException e) {
        System.out.println("Error: Invalid Command!");
      } catch (UserRuntimeException e) {
        System.out.printf("Error: %s%n", e.getMessage());
      }
    } while (option != 4);
  }

  private String parse(int option) {
    return switch (option) {
      case 1 -> this.submitULReport();
      case 2 -> this.obtainUL();
      case 3 -> this.requestMyProofs();
      case 4 -> "CommandReader: Shutting Down (...)";
      default -> "Error: Command Not Available!";
    };
  }

  private String submitULReport() {
    System.out.print("Epoch: ");
    return this.doSubmitULReport(Integer.parseInt(scanner.nextLine()));
  }

  public String doSubmitULReport(Integer epoch) {
    Map<String, byte[]> idProofs = this.uuFrontend.getIdProofs(epoch);
    if (idProofs == null) {
      return String.format("Not Enough Valid Users to Send Location Report at Epoch %d!", epoch);
    }
    this.csFrontend.submitULReport(epoch, idProofs);
    return String.format("User Location Report at Epoch %d Submitted Successfully!", epoch);
  }

  private String obtainUL() {
    System.out.print("Epoch: ");
    return this.doObtainUL(Integer.parseInt(scanner.nextLine()));
  }

  private String requestMyProofs() {
    System.out.print("Epochs: ");
    String line = scanner.nextLine();
    Map<Integer, List<String>> proofs =
        this.csFrontend.requestMyProofs(
            Arrays.stream(line.split(" ")).map(Integer::parseInt).collect(Collectors.toList()));

    StringBuilder stringBuilder = new StringBuilder();
    proofs.forEach(
        (epoch, epochProofs) -> {
          stringBuilder.append(String.format("Epoch: %s%n", epoch));
          epochProofs.forEach(stringBuilder::append);
        });
    return stringBuilder.toString();
  }

  private String doObtainUL(Integer epoch) {
    Location location = this.csFrontend.obtainUL(epoch);
    return String.format(
        "User Location at Epoch %d: (%d, %d)!", epoch, location.getX(), location.getY());
  }

  private void displayMenu() {
    System.out.println("============== Menu ===============");
    System.out.println("| 1 - Submit User Location Report |");
    System.out.println("| 2 - Obtain User Location        |");
    System.out.println("| 3 - Request My Proofs           |");
    System.out.println("| 4 - Exit                        |");
    System.out.println("===================================");
  }
}
