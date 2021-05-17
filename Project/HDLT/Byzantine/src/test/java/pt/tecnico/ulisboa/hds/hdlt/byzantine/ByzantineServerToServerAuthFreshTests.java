package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineServerToServerADEBFrontend;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;
import pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineServerToServerAuthFreshTests extends BaseTests {

  private static final String sName = "Server1";
  private static final String uname = "User1";
  private static Integer epoch;
  private static String destSName;
  private static DHFrontend dhFrontend;
  private static ByzantineServerToServerADEBFrontend sFrontend;

  @BeforeAll
  static void oneTimeSetUp() throws IOException {
    newServer();
  }

  private static void newServer() throws IOException {
    String sKSPath = String.format("%s/%s.jks", myServerKSDirPath, sName);
    String ksAlias = String.format("%s%s", myKeyStoreAlias, sName);
    String ksPwd = String.format("%s%s", myKeyStorePwd, sName);
    Iterator<Map.Entry<String, String>> entryIterator = myServersURLs.entrySet().iterator();
    destSName = entryIterator.next().getKey();
    destSName = destSName.equals(sName) ? entryIterator.next().getKey() : destSName;
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
    GridManager grid = new GridManager(uname, myGridPath);
    epoch = grid.getEpochs(uname).stream().findFirst().orElse(0);
    dhFrontend = new DHFrontend(sName, sCrypto, myServersURLs, myCallTimeout, myMaxNRetries);
    sFrontend = new ByzantineServerToServerADEBFrontend(sName, myServersURLs, dhFrontend);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    dhFrontend.shutdown();
    sFrontend.shutdown();
  }

  @Test
  public void echoNoFreshness() {
    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> sFrontend.echoNoFreshness(destSName, uReport));
    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void echoNoValidHMAC() {
    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> sFrontend.echoNoValidHMAC(destSName, uReport));

    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }

  @Test
  public void readyNoFreshness() {
    Report report =
        ClientToServerFrontend.buildReport(
            uname, epoch, new Location(0, 0), new HashMap<>(), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> sFrontend.readyNoFreshness(destSName, report));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void readyNoValidHMAC() {
    Report report =
        ClientToServerFrontend.buildReport(
            uname, epoch, new Location(0, 0), new HashMap<>(), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> sFrontend.readyNoValidHMAC(destSName, report));

    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }
}
