package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Common;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByzantineUserToUserTests extends BaseTests {

  private static final Map<String, UserToUserFrontend> uuFrontends = new HashMap<>();
  private static final Map<String, ByzantineUserToUserFrontend> buuFrontends = new HashMap<>();
  private static final Map<String, Server> uServers = new HashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws IOException {
    newUser("User1", true);
    newUser("User2", true);
    newUser("User3", false);
    newUser("User5", false);
  }

  private static void newUser(String uname, boolean isByzantine) throws IOException {
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
    BindableService uuBindableService;
    if (isByzantine) {
      ByzantineUserToUserFrontend buuFrontend =
          new ByzantineUserToUserFrontend(
              uname, grid, uCrypto, myNByzantineUsers, myMaxDistance, myUsersURLs);
      ByzantineUserUserServicesImpl buuService = new ByzantineUserUserServicesImpl(grid, uCrypto);
      buuFrontends.put(uname, buuFrontend);
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
  }

  @AfterAll
  public static void oneTimeTearDown() {
    uServers.values().forEach(Server::shutdown);
    uuFrontends.values().forEach(UserToUserFrontend::shutdown);
    buuFrontends.values().forEach(ByzantineUserToUserFrontend::shutdown);
  }

  @Test
  public void requestULProofToFarAwayUser() {
    Integer epoch = 1;
    String bUname = "User1";
    String uname = "User5";

    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                buuFrontends.get(bUname).getIdProofsFromFarAwayUser(epoch, myUsersURLs.get(uname)));

    assertEquals("FAILED_PRECONDITION: User Too Far Away!", exception.getMessage());
  }
}
