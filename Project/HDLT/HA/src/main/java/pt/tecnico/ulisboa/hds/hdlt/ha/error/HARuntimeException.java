package pt.tecnico.ulisboa.hds.hdlt.ha.error;

public class HARuntimeException extends RuntimeException {

  public HARuntimeException(Throwable e) {
    super(e);
  }

  public HARuntimeException(String errorMsg) {
    super(errorMsg);
  }
}
