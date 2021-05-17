package pt.tecnico.ulisboa.hds.hdlt.lib.error;

public class AssertError extends AssertionError {

  public AssertError(String className, String methodName, Exception e) {
    super(String.format("Assertion Error in Class: %s, Method: %s", className, methodName), e);
  }

  public AssertError(String className, String methodName) {
    super(String.format("Assertion Error in Class: %s, Method: %s", className, methodName));
  }

  public AssertError(String msg) {
    super(msg);
  }
}
