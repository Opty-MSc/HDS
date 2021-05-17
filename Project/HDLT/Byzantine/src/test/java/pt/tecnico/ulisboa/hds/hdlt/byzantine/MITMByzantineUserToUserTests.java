package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parsePort;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class MITMByzantineUserToUserTests extends BaseTests {

  private static final int callTimeout = 2;
  private static final int maxNRetries = 2;
  private static final Map<String, UserToUserFrontend> uuFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserToUserFrontend> buuFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserUserServicesImpl> buuServices = new HashMap<>();
  private static final Map<String, Server> uServers = new HashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws IOException {
    newUser("User1", true);
    newUser("User2", true);
    newUser("User3", false);
    newUser("User5", false);
  }

  private static void newUser(String uname, boolean isByzantine) throws IOException {
    GridManager grid = new GridManager(uname, myGridPath);
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
    BindableService uuBindableService;
    if (isByzantine) {
      ByzantineUserToUserFrontend buuFrontend =
          new ByzantineUserToUserFrontend(
              uname, grid, uCrypto, myNByzantineUsers, myMaxDistance, myUsersURLs);
      ByzantineUserUserServicesImpl buuService = new ByzantineUserUserServicesImpl(grid, uCrypto);
      buuFrontends.put(uname, buuFrontend);
      buuServices.put(uname, buuService);
      uuBindableService = buuService;
    } else {
      UserToUserFrontend uuFrontend =
          new UserToUserFrontend(
              uname,
              grid,
              uCrypto,
              myNByzantineUsers,
              myMaxDistance,
              myUsersURLs,
              callTimeout,
              maxNRetries);
      UserUserServicesImpl uuService =
          new UserUserServicesImpl(uname, grid, uCrypto, myMaxDistance);
      uuFrontends.put(uname, uuFrontend);
      uuBindableService = uuService;
    }
    int uPort = parsePort(myUsersURLs.get(uname).split(":")[1]);
    Server uServer = ServerBuilder.forPort(uPort).addService(uuBindableService).build();
    uServer.start();
    uServers.put(uname, uServer);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    uServers.values().forEach(Server::shutdown);
    uuFrontends.values().forEach(UserToUserFrontend::shutdown);
    buuFrontends.values().forEach(ByzantineUserToUserFrontend::shutdown);
  }

  @Test
  public void requestULMalformedExceptionProof() {
    Integer epoch = 1;
    String uname = "User1";
    String destUname = "User2";

    buuServices.get(destUname).setMode(MALFORMED_EXCEPTION);

    UserRuntimeException exception =
        assertThrows(
            UserRuntimeException.class,
            () -> {
              // This User Receives a Response which is not Properly Signed
              buuFrontends
                  .get(uname)
                  .getMalformedExceptionIdProofs(epoch, myUsersURLs.get(destUname), destUname);
            });
    assertEquals("Authenticity Tests Failed!", exception.getMessage());
  }

  @Test
  public void requestULProofDoesNotAnswer() {
    Integer epoch = 1;
    String uname = "User3";
    String bDestUname = "User1";
    String destUname = "User5";

    buuServices.get(bDestUname).setMode(DOES_NOT_ANSWER);

    new Thread(
            () -> {
              sleep(1000);
              buuServices.get(bDestUname).setMode(ALWAYS_SIGN);
            })
        .start();
    // This User Retries its Requests
    // Because a Man In the Middle is Dropping a Request
    Map<String, byte[]> idProofs = uuFrontends.get(uname).getIdProofs(epoch);
    assertTrue(idProofs.containsKey(destUname) && idProofs.containsKey(bDestUname));
  }
}
