package pt.tecnico.ulisboa.hds.hdlt.ha;

import pt.tecnico.ulisboa.hds.hdlt.ha.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.ha.error.HARuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;

import java.util.List;
import java.util.Scanner;

public class CommandReader {

  private static final Scanner scanner = new Scanner(System.in);
  private final ClientToServerFrontend csFrontend;

  public CommandReader(ClientToServerFrontend csFrontend) {
    this.csFrontend = csFrontend;
  }

  public static String parseUnames(Integer epoch, Location location, List<String> unames) {
    if (unames.size() == 0) {
      return String.format(
          "There are no Users near Location (%d, %d) at Epoch %d!",
          location.getX(), location.getY(), epoch);
    }
    StringBuilder sb =
        new StringBuilder(
            String.format(
                "Users near Location (%d, %d) at Epoch %d:%n",
                location.getX(), location.getY(), epoch));
    unames.forEach(uname -> sb.append(String.format("- %s%n", uname)));
    return sb.substring(0, sb.length() - 1);
  }

  public static String parseLocation(String uname, Integer epoch, Location location) {
    return String.format(
        "%s Location at Epoch %d: (%d, %d)!", uname, epoch, location.getX(), location.getY());
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
      } catch (HARuntimeException e) {
        System.out.printf("Error: %s%n", e.getMessage());
      }
    } while (option != 3);
  }

  private String parse(int option) {
    return switch (option) {
      case 1 -> this.obtainUL();
      case 2 -> this.obtainUAtL();
      case 3 -> "CommandReader: Shutting Down (...)";
      default -> "Error: Command Not Available!";
    };
  }

  private String obtainUL() {
    System.out.print("Username: ");
    String uname = scanner.nextLine();
    System.out.print("Epoch: ");
    Integer epoch = Integer.parseInt(scanner.nextLine());
    Location location = csFrontend.obtainUL(uname, epoch);
    return parseLocation(uname, epoch, location);
  }

  private String obtainUAtL() {
    System.out.println("(X, Y)");
    System.out.print("Coordinate X: ");
    Integer x = Integer.parseInt(scanner.nextLine());
    System.out.print("Coordinate Y: ");
    Integer y = Integer.parseInt(scanner.nextLine());
    System.out.print("Epoch: ");
    Integer epoch = Integer.parseInt(scanner.nextLine());
    Location location = new Location(x, y);
    return parseUnames(epoch, location, this.csFrontend.obtainUAtL(epoch, location));
  }

  private void displayMenu() {
    System.out.println("============== Menu ==============");
    System.out.println("| 1 - Obtain User Location       |");
    System.out.println("| 2 - Obtain Users At Location   |");
    System.out.println("| 3 - Exit                       |");
    System.out.println("==================================");
  }
}
