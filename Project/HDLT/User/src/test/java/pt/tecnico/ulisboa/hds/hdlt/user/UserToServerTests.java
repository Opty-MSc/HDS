package pt.tecnico.ulisboa.hds.hdlt.user;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.*;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.ObtainULRepPayload;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.user.api.*;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parsePort;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UserToServerTests extends BaseTests {

  private static final Integer nUsers = 10;
  private static final Map<String, GridManager> grids = new HashMap<>();
  private static final Map<String, UserToDHServerFrontend> dhFrontends = new HashMap<>();
  private static final Map<String, UserToUserFrontend> uuFrontends = new HashMap<>();
  private static final Map<String, ClientToServerFrontend> csFrontends = new HashMap<>();
  private static final Map<String, Server> uServers = new HashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws IOException {
    for (int i = 1; i <= nUsers; i++) {
      String uname = String.format("User%d", i);
      String uKSPath = String.format("%s/%s.jks", myUserKSDirPath, uname);
      String ksAlias = String.format("%s%s", myKeyStoreAlias, uname);
      String ksPwd = String.format("%s%s", myKeyStorePwd, uname);
      GridManager grid = new GridManager(uname, myGridPath);
      UserCrypto uCrypto =
          new UserCrypto(
              uKSPath,
              myUserCrtDirPath,
              myServerCrtDirPath,
              ksAlias,
              ksPwd,
              mySessionTime,
              myPowDifficulty);
      UserToDHServerFrontend dhFrontend =
          new UserToDHServerFrontend(uname, uCrypto, myServersURLs, myCallTimeout, myMaxNRetries);
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
      ClientToServerFrontend usFrontend =
          new ClientToServerFrontend(
              uname,
              grid,
              uCrypto,
              myServersURLs,
              myNByzantineServers,
              myNByzantineUsers,
              dhFrontend,
              myCallTimeout,
              myMaxNRetries);
      int uPort = parsePort(myUsersURLs.get(uname).split(":")[1]);
      Server uServer =
          ServerBuilder.forPort(uPort)
              .addService(new UserUserServicesImpl(uname, grid, uCrypto, myMaxDistance))
              .build();
      uServer.start();
      uServers.put(uname, uServer);
      dhFrontends.put(uname, dhFrontend);
      uuFrontends.put(uname, uuFrontend);
      csFrontends.put(uname, usFrontend);
      grids.put(uname, grid);
    }
  }

  @AfterAll
  public static void oneTimeTearDown() {
    uServers.values().forEach(Server::shutdown);
    dhFrontends.values().forEach(UserToDHServerFrontend::shutdown);
    uuFrontends.values().forEach(UserToUserFrontend::shutdown);
    csFrontends.values().forEach(ClientToServerFrontend::shutdown);
  }

  @Test
  @Order(1)
  public void submitUL() {
    String uname = "User2";
    Integer epoch = 1;
    try {
      Map<String, byte[]> idProofs = uuFrontends.get(uname).getIdProofs(epoch);
      if (idProofs != null) {
        csFrontends.get(uname).submitULReport(epoch, idProofs);
        return;
      }
    } catch (UserRuntimeException e) {
      fail(e.getMessage());
    }
    fail("IdProofs are null!");
  }

  @Test
  @Order(2)
  public void submitULWrittenInByzantineQuorum() {
    String uname = "User2";
    Integer epoch = 1;
    Map<ObtainULRepPayload, Integer> repPayloads = new HashMap<>();
    for (String sName : myServersURLs.keySet()) {
      ObtainULRepPayload repPayload =
          csFrontends.get(uname).doObtainUL(sName, epoch, new CountDownLatch(1), 0);
      Integer nReplies = repPayloads.putIfAbsent(repPayload, 1);
      if (nReplies != null) repPayloads.put(repPayload, nReplies + 1);
    }
    assertEquals(
        grids.get(uname).getLocation(uname, epoch),
        repPayloads.entrySet().stream()
            .filter(e -> e.getValue() >= 2 * myNByzantineServers + 1)
            .map(Map.Entry::getKey)
            .map(repPayload -> new Location(repPayload.getX(), repPayload.getY()))
            .findAny()
            .orElse(null));
  }

  @Test
  @Order(3)
  public void obtainUL() {
    String uname = "User2";
    Integer epoch = 1;
    assertEquals(
        grids.get(uname).getLocation(uname, epoch), csFrontends.get(uname).obtainUL(epoch));
  }

  @Test
  @Order(4)
  public void submitULThatAlreadyExists() {
    String uname = "User2";
    Integer epoch = 1;
    try {
      Map<String, byte[]> idProofs = uuFrontends.get(uname).getIdProofs(epoch);
      if (idProofs != null) {
        csFrontends.get(uname).submitULReport(epoch, idProofs);
        fail("Submit was successful!");
        return;
      }
    } catch (UserRuntimeException e) {
      assertEquals("ALREADY_EXISTS: Report Already Exists!", e.getMessage());
      return;
    }
    fail("IdProofs are null!");
  }

  @Test
  @Order(5)
  public void obtainULNotPresent() {
    String uname = "User3";
    Integer epoch = 1;
    UserRuntimeException exception =
        assertThrows(UserRuntimeException.class, () -> csFrontends.get(uname).obtainUL(epoch));
    assertEquals("NOT_FOUND: No Report Found!", exception.getMessage());
  }

  @Test
  @Order(4)
  public void requestMyProofs() {
    String uname = "User9";
    String sUname = "User5";
    Integer epoch = 1;

    Map<Integer, List<String>> proofsSigned = null;
    try {
      /* Make sure that sUname signs a proof to uname */
      Map<String, byte[]> idProofs = null;
      while (idProofs == null || !idProofs.containsKey(sUname)) {
        idProofs = uuFrontends.get(uname).getIdProofs(epoch);
      }
      csFrontends.get(uname).submitULReport(epoch, idProofs);
      proofsSigned = csFrontends.get(sUname).requestMyProofs(Collections.singletonList(epoch));
    } catch (UserRuntimeException e) {
      fail(e.getMessage());
    }
    Location uLocation = grids.get(uname).getLocation(uname, epoch);
    String expected =
        String.format("- %s at Location: (%d, %d)!%n", uname, uLocation.getX(), uLocation.getY());
    assertTrue(proofsSigned.containsKey(epoch));
    assertEquals(expected, proofsSigned.get(epoch).get(0));
  }
}
