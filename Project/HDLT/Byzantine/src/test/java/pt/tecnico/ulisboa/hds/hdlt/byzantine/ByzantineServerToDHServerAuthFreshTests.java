package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineServerToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineServerToDHServerAuthFreshTests extends BaseTests {

  private static final String sName = "Server1";
  private static ByzantineServerToDHServerFrontend dhFrontend;

  @BeforeAll
  static void oneTimeSetUp() {
    newServer();
  }

  private static void newServer() {
    String sKSPath = String.format("%s/%s.jks", myServerKSDirPath, sName);
    String ksAlias = String.format("%s%s", myKeyStoreAlias, sName);
    String ksPwd = String.format("%s%s", myKeyStorePwd, sName);
    ServerCrypto sCrypto =
        new ServerCrypto(
            sKSPath,
            myServerCrtDirPath,
            myUserCrtDirPath,
            myHACrtPath,
            ksAlias,
            ksPwd,
            new SessionsManager(mySessionTime),
            myPowDifficulty);
    dhFrontend =
        new ByzantineServerToDHServerFrontend(
            sName, sCrypto, myServersURLs.entrySet().iterator().next().getValue());
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
