package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineClientServerServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineDHServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ExceptionHandler;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;
import pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineClientServerServicesImpl.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class MITMByzantineUserToServerTests extends BaseTests {

  private static final String uname = "User1";
  private static final String sName = "Server1";
  private static final String sHost = "localhost";
  private static final int sPort = 8090;
  private static final int callTimeout = 2;
  private static final int maxNRetries = 2;
  private static int epoch;
  private static UserToDHServerFrontend dhFrontend;
  private static ClientToServerFrontend csFrontend;
  private static ByzantineClientServerServicesImpl bsImpl;
  private static Server csServer;

  @BeforeAll
  static void oneTimeSetUp() throws IOException {
    newUser();
  }

  private static void newUser() throws IOException {
    Map<String, String> sURLs = new HashMap<>();
    sURLs.put(sName, String.format("%s:%d", sHost, sPort));
    GridManager grid = new GridManager(uname, myGridPath);
    String uKSPath = String.format("%s/%s.jks", myUserKSDirPath, uname);
    String uKsAlias = String.format("%s%s", myKeyStoreAlias, uname);
    String uKsPwd = String.format("%s%s", myKeyStorePwd, uname);
    String sKsAlias = String.format("%s%s", myKeyStoreAlias, sName);
    String sKsPwd = String.format("%s%s", myKeyStorePwd, sName);
    UserCrypto uCrypto =
        new UserCrypto(
            uKSPath,
            myUserCrtDirPath,
            myServerCrtDirPath,
            uKsAlias,
            uKsPwd,
            mySessionTime,
            myPowDifficulty);
    String sKSPath = String.format("%s/%s.jks", myServerKSDirPath, sName);
    SessionsManager sessionsManager = new SessionsManager(mySessionTime);
    ServerCrypto sCrypto =
        new ServerCrypto(
            sKSPath,
            myServerCrtDirPath,
            myUserCrtDirPath,
            myHACrtPath,
            sKsAlias,
            sKsPwd,
            sessionsManager,
            myPowDifficulty);
    epoch = grid.getEpochs(uname).stream().findFirst().orElseThrow();
    dhFrontend = new UserToDHServerFrontend(uname, uCrypto, sURLs, callTimeout, maxNRetries);
    csFrontend =
        new ClientToServerFrontend(
            uname,
            grid,
            uCrypto,
            sURLs,
            0,
            myNByzantineUsers,
            dhFrontend,
            callTimeout,
            maxNRetries);
    bsImpl = new ByzantineClientServerServicesImpl(sCrypto);
    csServer =
        ServerBuilder.forPort(sPort)
            .addService(bsImpl)
            .addService(new ByzantineDHServicesImpl(sCrypto))
            .intercept(new ExceptionHandler())
            .build();
    csServer.start();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    dhFrontend.shutdown();
    csFrontend.shutdown();
    csServer.shutdown();
  }

  @Test
  public void submitULReportWhereServerReject() {
    bsImpl.setMode(UNSIGNED_EXCEPTION);
    new Thread(
            () -> {
              sleep(1000);
              bsImpl.setMode(SIGN_EXCEPTION);
            })
        .start();
    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class, () -> csFrontend.submitULReport(epoch, new HashMap<>()));

    // This User Retries its Requests
    // Because a Man In the Middle is Rejecting a Request
    assertEquals("UNKNOWN: Exception Signed!", exception.getMessage());
  }

  @Test
  public void submitULReportDoesNotAnswer() {
    bsImpl.setMode(NEVER_SIGN_EXCEPTION);
    new Thread(
            () -> {
              sleep(1000);
              bsImpl.setMode(SIGN_EXCEPTION);
            })
        .start();
    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class, () -> csFrontend.submitULReport(epoch, new HashMap<>()));

    // This User Retries its Requests
    // Because a Man In the Middle is Dropping a Request
    assertEquals("UNKNOWN: Exception Signed!", exception.getMessage());
  }

  @Test
  public void obtainULWhereServerReject() {
    bsImpl.setMode(UNSIGNED_EXCEPTION);
    new Thread(
            () -> {
              sleep(1000);
              bsImpl.setMode(SIGN_EXCEPTION);
            })
        .start();
    UserRuntimeException exception =
        assertThrows(UserRuntimeException.class, () -> csFrontend.obtainUL(epoch));

    // This User Retries its Requests
    // Because a Man In the Middle is Rejecting a Request
    assertEquals("UNKNOWN: Exception Signed!", exception.getMessage());
  }

  @Test
  public void obtainULDoesNotAnswer() {
    bsImpl.setMode(NEVER_SIGN_EXCEPTION);
    new Thread(
            () -> {
              sleep(1000);
              bsImpl.setMode(SIGN_EXCEPTION);
            })
        .start();
    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class, () -> csFrontend.submitULReport(epoch, new HashMap<>()));

    // This User Retries its Requests
    // Because a Man In the Middle is Dropping a Request
    assertEquals("UNKNOWN: Exception Signed!", exception.getMessage());
  }
}
