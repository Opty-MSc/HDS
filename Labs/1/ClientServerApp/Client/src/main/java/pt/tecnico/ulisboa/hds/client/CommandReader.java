package pt.tecnico.ulisboa.hds.client;

import pt.tecnico.ulisboa.hds.client.api.ClientFrontend;
import pt.tecnico.ulisboa.hds.client.api.ClientFrontendError;

import java.util.Scanner;

public class CommandReader {

  private static final Scanner scanner = new Scanner(System.in);
  private final ClientFrontend frontend;

  public CommandReader(ClientFrontend frontend) {
    this.frontend = frontend;
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
      }
    } while (option != 1);
  }

  private String parse(int option) {
    return switch (option) {
      case 0 -> echo();
      case 1 -> "Goodbye!";
      default -> "Error: Command Not Available!";
    };
  }

  private String echo() {
    System.out.print(String.format("Message:%n>>> "));
    String msg = scanner.nextLine();
    try {
      return frontend.echo(msg);
    } catch (ClientFrontendError e) {
      return String.format("Error: %s", e.getMessage());
    }
  }

  private void displayMenu() {
    System.out.println("============== Menu ==============");
    System.out.println("| 0 - Echo                       |");
    System.out.println("| 1 - Exit                       |");
    System.out.println("==================================");
  }
}
