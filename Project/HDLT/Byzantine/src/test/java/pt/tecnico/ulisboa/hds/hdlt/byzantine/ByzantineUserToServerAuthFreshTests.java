package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineUserToServerAuthFreshTests extends BaseTests {

  private static final String uname = "User1";
  private static final String sName = "Server1";
  private static Integer epoch;
  private static UserToDHServerFrontend dhFrontend;
  private static ByzantineUserToServerFrontend bsFrontend;

  @BeforeAll
  static void oneTimeSetUp() throws IOException {
    newUser();
    dhFrontend.dH(sName, 0);
  }

  private static void newUser() throws IOException {
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
    GridManager grid = new GridManager(uname, myGridPath);
    epoch = grid.getEpochs(uname).stream().findFirst().orElse(0);
    dhFrontend =
        new UserToDHServerFrontend(uname, uCrypto, myServersURLs, myCallTimeout, myMaxNRetries);
    bsFrontend = new ByzantineUserToServerFrontend(uname, uCrypto, myServersURLs, dhFrontend);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    dhFrontend.shutdown();
    bsFrontend.shutdown();
  }

  @Test
  public void submitULReportFreshness() {
    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.submitULReportNoFreshness(sName, uReport));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void submitULReportNoValidHMAC() {
    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.submitULReportNoValidHMAC(sName, uReport));

    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }

  @Test
  public void obtainULNoFreshness() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> bsFrontend.obtainULNoFreshness(sName, epoch));
    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void obtainULNoValidHMAC() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class, () -> bsFrontend.obtainULNoValidHMAC(sName, epoch));
    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }

  @Test
  public void requestMyProofsNoFreshness() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.requestMyProofsNoFreshness(sName, new ArrayList<>(epoch)));
    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void requestMyProofsNoValidHMAC() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.requestMyProofsNoValidHMAC(sName, new ArrayList<>(epoch)));
    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }
}
