package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.ObtainULRepPayload;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Common;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices;
import pt.tecnico.ulisboa.hds.hdlt.user.api.*;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;
import pt.tecnico.ulisboa.hds.hdlt.user.session.Session;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl.ALWAYS_SIGN;
import static pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl.NEVER_SIGN;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.await;
import static pt.tecnico.ulisboa.hds.hdlt.user.api.ClientToServerFrontend.buildUserReport;

public class ByzantineUserToServerTests extends BaseTests {

  private static final Map<String, UserCrypto> uCryptos = new HashMap<>();
  private static final Map<String, GridManager> grids = new HashMap<>();
  private static final Map<String, UserToDHServerFrontend> dhFrontends = new HashMap<>();
  private static final Map<String, ClientToServerFrontend> csFrontends = new HashMap<>();
  private static final Map<String, UserToUserFrontend> uuFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserToUserFrontend> buuFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserToServerFrontend> busFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserUserServicesImpl> buuServices = new HashMap<>();
  private static final Map<String, Server> uServers = new HashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws IOException {
    newUser("User1", true, false, true);
    newUser("User2", true, false, false);
    newUser("User3", true, false, false);
    newUser("User4", true, false, false);
    newUser("User5", false, false, false);
    newUser("User6", false, false, false);
    newUser("User7", false, false, false);
    newUser("User8", true, false, false);
    newUser("User9", false, false, false);
    newUser("User10", false, true, false);
  }

  private static void newUser(
      String uname, boolean isUUByzantine, boolean isCSByzantine, boolean isImpersonator)
      throws IOException {
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
    String fUname =
        isImpersonator
            ? myUsersURLs.keySet().stream().filter(u -> !u.equals(uname)).findFirst().orElseThrow()
            : uname;
    UserToDHServerFrontend dhFrontend =
        new UserToDHServerFrontend(fUname, uCrypto, myServersURLs, myCallTimeout, myMaxNRetries);
    GridManager grid = new GridManager(uname, myGridPath);
    if (isCSByzantine) {
      ByzantineUserToServerFrontend busFrontend =
          new ByzantineUserToServerFrontend(fUname, uCrypto, myServersURLs, dhFrontend);
      busFrontends.put(fUname, busFrontend);
    } else {
      ClientToServerFrontend csFrontend =
          new ClientToServerFrontend(
              fUname,
              grid,
              uCrypto,
              myServersURLs,
              myNByzantineServers,
              myNByzantineUsers,
              dhFrontend,
              myCallTimeout,
              myMaxNRetries);
      csFrontends.put(uname, csFrontend);
    }
    BindableService uuBindableService;
    if (isUUByzantine) {
      ByzantineUserToUserFrontend buuFrontend =
          new ByzantineUserToUserFrontend(
              fUname, grid, uCrypto, myNByzantineUsers, myMaxDistance, myUsersURLs);
      ByzantineUserUserServicesImpl buuService = new ByzantineUserUserServicesImpl(grid, uCrypto);
      buuFrontends.put(uname, buuFrontend);
      buuServices.put(uname, buuService);
      uuBindableService = buuService;
    } else {
      UserToUserFrontend uuFrontend =
          new UserToUserFrontend(
              fUname,
              grid,
              uCrypto,
              myNByzantineUsers,
              myMaxDistance,
              myUsersURLs,
              myCallTimeout,
              myMaxNRetries);
      UserUserServicesImpl uuService =
          new UserUserServicesImpl(uname, grid, uCrypto, myMaxDistance);
      uuFrontends.put(uname, uuFrontend);
      uuBindableService = uuService;
    }
    int uPort = Common.parsePort(myUsersURLs.get(uname).split(":")[1]);
    Server uServer = ServerBuilder.forPort(uPort).addService(uuBindableService).build();
    uServer.start();
    uServers.put(uname, uServer);
    uCryptos.put(uname, uCrypto);
    dhFrontends.put(uname, dhFrontend);
    grids.put(uname, grid);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    uServers.values().forEach(Server::shutdown);
    dhFrontends.values().forEach(UserToDHServerFrontend::shutdown);
    csFrontends.values().forEach(ClientToServerFrontend::shutdown);
    uuFrontends.values().forEach(UserToUserFrontend::shutdown);
    buuFrontends.values().forEach(ByzantineUserToUserFrontend::shutdown);
    busFrontends.values().forEach(ByzantineUserToServerFrontend::shutdown);
  }

  @Test
  public void obtainULImpersonator() {
    String uname = "User1";
    Integer epoch = 1;

    UserRuntimeException exception =
        assertThrows(UserRuntimeException.class, () -> csFrontends.get(uname).obtainUL(epoch));
    assertEquals("INVALID_ARGUMENT: Possible Impersonation Attack!", exception.getMessage());
  }

  @Test
  public void submitULImpersonator() {
    String uname = "User1";
    Integer epoch = 1;

    buuServices.get("User2").setMode(ALWAYS_SIGN);
    buuServices.get("User3").setMode(ALWAYS_SIGN);

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // Getting Some Proofs of Byzantine Users
              Map<String, byte[]> idProofs =
                  buuFrontends.get(uname).getIdProofsImpersonation(epoch);
              csFrontends.get(uname).submitULReport(epoch, idProofs);
            });
    assertEquals("INVALID_ARGUMENT: Possible Impersonation Attack!", exception.getMessage());
  }

  @Test
  public void submitULSelfGeneratedProofs() {
    String uname = "User2";
    Integer epoch = 1;

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Attempts to Create Location Proofs itself!
              Map<String, byte[]> idProofs =
                  buuFrontends.get(uname).getSelfGeneratedIdProofs(epoch);
              csFrontends.get(uname).submitULReport(epoch, idProofs);
            });
    assertEquals("INVALID_ARGUMENT: Number of User Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void submitULReplicatedProofs() {
    String uname = "User2";
    Integer epoch = 1;

    buuServices.get("User1").setMode(ALWAYS_SIGN);

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Tries to Replicate Location Proofs Made By Another Byzantine User!
              Map<String, byte[]> idProofs =
                  buuFrontends.get(uname).getReplicatedIdProofs(epoch, "User1");
              csFrontends.get(uname).submitULReport(epoch, idProofs);
            });
    assertEquals("INVALID_ARGUMENT: Number of User Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void submitULWithoutProofs() {
    String uname = "User2";
    Integer epoch = 1;

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Tries to Submit its Location With no Proofs!
              csFrontends.get(uname).submitULReport(epoch, new HashMap<>());
            });
    assertEquals("INVALID_ARGUMENT: Number of User Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void submitULWithInsufficientProofs() {
    String uname = "User2";
    Integer epoch = 1;

    buuServices.get("User1").setMode(NEVER_SIGN);
    buuServices.get("User4").setMode(NEVER_SIGN);

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Does Not Get Enough Location Proofs!
              Map<String, byte[]> idProofs =
                  buuFrontends.get(uname).getIdProofsImpersonation(epoch);
              csFrontends.get(uname).submitULReport(epoch, idProofs);
            });
    assertEquals("INVALID_ARGUMENT: Number of User Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void obtainULWriteBackWithoutUserProofs() {
    String uname = "User5";
    Integer epoch = 1;

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Tries to WriteBack a Report Without User Proofs
              Location location = grids.get(uname).getLocation(uname, epoch);
              csFrontends
                  .get(uname)
                  .obtainULWriteBack(
                      new HashSet<>(), epoch, location, new HashMap<>(), new HashMap<>());
            });
    assertEquals("INVALID_ARGUMENT: Number of User Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void obtainULWriteBackWithoutServerProofs() {
    String uname = "User5";
    Integer epoch = 1;

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Tries to WriteBack a Report Without Server Proofs
              Location location = grids.get(uname).getLocation(uname, epoch);
              Map<String, byte[]> idProofs = uuFrontends.get(uname).getIdProofs(epoch);
              csFrontends
                  .get(uname)
                  .obtainULWriteBack(
                      new HashSet<>(),
                      epoch,
                      location,
                      CommonServices.toByteString(idProofs),
                      new HashMap<>());
            });
    assertEquals("INVALID_ARGUMENT: Number of Server Proofs Insufficient!", exception.getMessage());
  }

  @Test
  public void submitULReportInvalidPow() {
    String uname = "User10";

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> {
              // This User Tries to Submit its Location With an Invalid Proof of Work
              busFrontends
                  .get(uname)
                  .submitULReportInvalidPow(
                      myServersURLs.keySet().iterator().next(), UserReport.getDefaultInstance());
            });
    assertEquals("INVALID_ARGUMENT: Invalid Proof of Work!", exception.getMessage());
  }

  @Test
  public void obtainULWriteBackInvalidPow() {
    String uname = "User10";

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> {
              // This User Tries to WriteBack a Report With an Invalid Proof of Work
              busFrontends
                  .get(uname)
                  .obtainULWriteBackInvalidPow(
                      myServersURLs.keySet().iterator().next(), Report.getDefaultInstance());
            });
    assertEquals("INVALID_ARGUMENT: Invalid Proof of Work!", exception.getMessage());
  }

  @Test
  public void submitULReportWithInvalidSession() {
    String bUname = "User10";
    String sName = "Server1";

    dhFrontends.get(bUname).dH(sName, 0);
    Session oldSession = uCryptos.get(bUname).getSession(sName);
    uCryptos.get(bUname).removeSession(sName);
    dhFrontends.get(bUname).dH(sName, 0);

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                busFrontends
                    .get(bUname)
                    .submitULReportInvalidSession(
                        oldSession, sName, UserReport.getDefaultInstance()));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void obtainULWithInvalidSession() {
    String bUname = "User10";
    String sName = "Server1";
    Integer epoch = 1;

    dhFrontends.get(bUname).dH(sName, 0);
    Session oldSession = uCryptos.get(bUname).getSession(sName);
    uCryptos.get(bUname).removeSession(sName);
    dhFrontends.get(bUname).dH(sName, 0);

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> busFrontends.get(bUname).obtainULInvalidSession(oldSession, sName, epoch));

    assertEquals("UNAUTHENTICATED: Freshness Tests Failed!", exception.getMessage());
  }

  @Test
  public void submitDiffULReportsToDiffServers() {
    String bUname = "User8";
    Integer epoch = 1;

    buuServices.get("User3").setMode(ALWAYS_SIGN);

    Map<String, byte[]> idProofs = new HashMap<>();
    List<String> closeUsers = grids.get(bUname).getCloseUsers(epoch, myMaxDistance);
    for (String uname : closeUsers) {
      idProofs.put(
          uname, buuFrontends.get(bUname).getIdProofsFromUser(epoch, myUsersURLs.get(uname)));
    }

    UserReport uReport1 = generateUserReport(bUname, epoch, idProofs);
    idProofs.remove(closeUsers.get(0));
    UserReport uReport2 = generateUserReport(bUname, epoch, idProofs);

    AtomicInteger nMsg = new AtomicInteger(0);
    CountDownLatch countDownLatch = new CountDownLatch(2 * myNByzantineServers + 1);
    Map<String, Integer> exceptions = new HashMap<>();
    for (String sName : myServersURLs.keySet()) {
      new Thread(
              () ->
                  csFrontends
                      .get(bUname)
                      .submitULReportWorker(
                          sName,
                          nMsg.getAndIncrement() == 0 ? uReport1 : uReport2,
                          countDownLatch,
                          exceptions))
          .start();
    }
    await(countDownLatch);
    if (exceptions.size() > 0) fail("Exception Thrown!");

    Map<ObtainULRepPayload, Integer> repPayloads = new HashMap<>();
    for (String sName : myServersURLs.keySet()) {
      ObtainULRepPayload repPayload =
          csFrontends.get(bUname).doObtainUL(sName, epoch, new CountDownLatch(1), 0);
      Integer nReplies = repPayloads.putIfAbsent(repPayload, 1);
      if (nReplies != null) repPayloads.put(repPayload, nReplies + 1);
    }
    assertEquals(
        grids.get(bUname).getLocation(bUname, epoch),
        repPayloads.entrySet().stream()
            .filter(e -> e.getValue() >= 2 * myNByzantineServers + 1)
            .map(Map.Entry::getKey)
            .map(repPayload -> new Location(repPayload.getX(), repPayload.getY()))
            .findAny()
            .orElse(null));
  }

  private UserReport generateUserReport(String uname, Integer epoch, Map<String, byte[]> idProofs) {
    return buildUserReport(
        uname,
        epoch,
        grids.get(uname).getLocation(uname, epoch),
        CommonServices.toByteString(idProofs));
  }
}
