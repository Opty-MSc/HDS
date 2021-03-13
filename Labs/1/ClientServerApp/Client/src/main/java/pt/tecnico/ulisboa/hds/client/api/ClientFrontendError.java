package pt.tecnico.ulisboa.hds.client.api;

public class ClientFrontendError extends Exception {
  public ClientFrontendError(String msg, Exception e) {
    super(msg, e);
  }

  public ClientFrontendError(String msg) {
    super(msg);
  }
}
