package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineUserToDHServerAuthFreshTests extends BaseTests {

  private static final String uname = "User1";
  private static ByzantineUserToDHServerFrontend dhFrontend;

  @BeforeAll
  static void oneTimeSetUp() {
    newUser();
  }

  private static void newUser() {
    String uKSPath = String.format("%s/%s.jks", myUserKSDirPath, uname);
    String ksAlias = String.format("%s%s", myKeyStoreAlias, uname);
    String ksPwd = String.format("%s%s", myKeyStorePwd, uname);
    UserCrypto uCrypto =
        new UserCrypto(
            uKSPath,
            myUserCrtDirPath,
            myServerCrtDirPath,
            ksAlias,
            ksPwd,
            mySessionTime,
            myPowDifficulty);
    dhFrontend =
        new ByzantineUserToDHServerFrontend(
            uname, uCrypto, myServersURLs.entrySet().iterator().next().getValue());
  }

  @AfterAll
  public static void oneTimeTearDown() {
    dhFrontend.shutdown();
  }

  @Test
  public void dHNoFreshness() {
    StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> dhFrontend.dHNoFreshness());
    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void dHNoValidSignature() {
    StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> dhFrontend.dHNoValidSignature());
    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }
}
