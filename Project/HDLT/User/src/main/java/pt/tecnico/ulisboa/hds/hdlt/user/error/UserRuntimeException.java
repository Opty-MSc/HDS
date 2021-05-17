package pt.tecnico.ulisboa.hds.hdlt.user.error;

public class UserRuntimeException extends RuntimeException {

  public UserRuntimeException(Throwable e) {
    super(e);
  }

  public UserRuntimeException(String errorMsg) {
    super(errorMsg);
  }
}
