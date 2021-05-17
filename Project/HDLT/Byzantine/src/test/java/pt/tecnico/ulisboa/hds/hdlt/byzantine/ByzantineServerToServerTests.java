package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineServerToServerADEBFrontend;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Common;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;
import pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineServerToServerTests extends BaseTests {

  private static final String uname = "User1";
  private static final String sName = "Server1";
  private static final Map<String, UserToUserFrontend> uuFrontends = new HashMap<>();
  private static final Map<String, Server> uServers = new HashMap<>();
  private static String destSName;
  private static Integer epoch;
  private static GridManager grid;
  private static ServerCrypto sCrypto;
  private static DHFrontend dhFrontend;
  private static ADEBFrontend sFrontend;
  private static ByzantineServerToServerADEBFrontend bsFrontend;

  @BeforeAll
  static void oneTimeSetUp() throws IOException {
    newUser("User1");
    newUser("User2");
    newUser("User3");
    newUser("User4");
    newUser("User5");
    newUser("User6");
    newUser("User7");
    newUser("User8");
    newUser("User9");
    newUser("User10");
    newServer();
  }

  private static void newUser(String uname) throws IOException {
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
    grid = new GridManager(uname, myGridPath);
    UserToUserFrontend uuFrontend =
        new UserToUserFrontend(
            uname,
            grid,
            uCrypto,
            myNByzantineUsers,
            myMaxDistance,
            myUsersURLs,
            myCallTimeout,
            myMaxNRetries);
    uuFrontends.put(uname, uuFrontend);
    int uPort = Common.parsePort(myUsersURLs.get(uname).split(":")[1]);
    Server uServer =
        ServerBuilder.forPort(uPort)
            .addService(new UserUserServicesImpl(uname, grid, uCrypto, myMaxDistance))
            .build();
    uServer.start();
    uServers.put(uname, uServer);
  }

  private static void newServer() throws IOException {
    String sKSPath = String.format("%s/%s.jks", myServerKSDirPath, sName);
    String ksAlias = String.format("%s%s", myKeyStoreAlias, sName);
    String ksPwd = String.format("%s%s", myKeyStorePwd, sName);
    Iterator<Map.Entry<String, String>> entryIterator = myServersURLs.entrySet().iterator();
    destSName = entryIterator.next().getKey();
    destSName = destSName.equals(sName) ? entryIterator.next().getKey() : destSName;

    GridManager grid = new GridManager(uname, myGridPath);
    epoch = grid.getEpochs(uname).stream().findFirst().orElse(0);
    sCrypto =
        new ServerCrypto(
            sKSPath,
            myServerCrtDirPath,
            myUserCrtDirPath,
            myHACrtPath,
            ksAlias,
            ksPwd,
            new SessionsManager(mySessionTime),
            myPowDifficulty);
    dhFrontend = new DHFrontend(sName, sCrypto, myServersURLs, myCallTimeout, myMaxNRetries);
    sFrontend =
        new ADEBFrontend(sName, sCrypto, myServersURLs, dhFrontend, myCallTimeout, myMaxNRetries);
    bsFrontend = new ByzantineServerToServerADEBFrontend(sName, myServersURLs, dhFrontend);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    uServers.values().forEach(Server::shutdown);
    uuFrontends.values().forEach(UserToUserFrontend::shutdown);
    dhFrontend.shutdown();
    sFrontend.shutdown();
  }

  @Test
  public void echoInvalidUserReport() {
    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    ServerStatusRuntimeException exception =
        assertThrows(
            ServerStatusRuntimeException.class,
            () -> sFrontend.echo(destSName, uReport, myMaxNRetries));

    assertEquals(
        "RESOURCE_EXHAUSTED: INVALID_ARGUMENT: Number of User Proofs Insufficient!",
        exception.getMessage());
  }

  @Test
  public void readyInvalidUserReport() {
    Report report =
        ClientToServerFrontend.buildReport(
            uname, epoch, new Location(0, 0), new HashMap<>(), new HashMap<>());

    ServerStatusRuntimeException exception =
        assertThrows(
            ServerStatusRuntimeException.class,
            () -> sFrontend.ready(destSName, report, myMaxNRetries));

    assertEquals(
        "RESOURCE_EXHAUSTED: INVALID_ARGUMENT: Number of User Proofs Insufficient!",
        exception.getMessage());
  }

  @Test
  public void readyInvalidReport() {
    Report report =
        ClientToServerFrontend.buildReport(
            uname,
            epoch,
            grid.getLocation(uname, epoch),
            CommonServices.toByteString(uuFrontends.get(uname).getIdProofs(epoch)),
            new HashMap<>());

    ServerStatusRuntimeException exception =
        assertThrows(
            ServerStatusRuntimeException.class,
            () -> sFrontend.ready(destSName, report, myMaxNRetries));

    assertEquals(
        "RESOURCE_EXHAUSTED: INVALID_ARGUMENT: No Valid Signatures!", exception.getMessage());
  }

  @Test
  public void echoWithInvalidSession() {
    dhFrontend.dH(destSName, 0);
    Session oldSession = sCrypto.getSessionAsClient(destSName);
    sCrypto.removeSessionAsClient(destSName);
    dhFrontend.dH(destSName, 0);

    UserReport uReport =
        ClientToServerFrontend.buildUserReport(uname, epoch, new Location(0, 0), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.echoWithInvalidSession(oldSession, destSName, uReport));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void readyWithInvalidSession() {
    dhFrontend.dH(destSName, 0);
    Session oldSession = sCrypto.getSessionAsClient(destSName);
    sCrypto.removeSessionAsClient(destSName);
    dhFrontend.dH(destSName, 0);

    Report report =
        ClientToServerFrontend.buildReport(
            uname, epoch, new Location(0, 0), new HashMap<>(), new HashMap<>());

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> bsFrontend.readyWithInvalidSession(oldSession, destSName, report));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }
}
