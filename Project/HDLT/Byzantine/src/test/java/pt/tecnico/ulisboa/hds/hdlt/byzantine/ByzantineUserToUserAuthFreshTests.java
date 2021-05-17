package pt.tecnico.ulisboa.hds.hdlt.byzantine;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.byzantine.api.ByzantineUserToUserFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parsePort;

public class ByzantineUserToUserAuthFreshTests extends BaseTests {

  private static final String uname = "User1";
  private static Integer epoch;
  private static String uUrl;
  private static ByzantineUserToUserFrontend buFrontend;
  private static Server buServer;

  @BeforeAll
  static void oneTimeSetUp() throws IOException {
    newUser();
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
    buFrontend =
        new ByzantineUserToUserFrontend(uname, grid, uCrypto, myNByzantineUsers, 1, myUsersURLs);
    String unameDest = grid.getCloseUsers(epoch, myMaxDistance).stream().findFirst().orElse("");
    uUrl = myUsersURLs.get(unameDest);
    int uPort = parsePort(uUrl.split(":")[1]);
    buServer =
        ServerBuilder.forPort(uPort)
            .addService(new UserUserServicesImpl(unameDest, grid, uCrypto, myMaxDistance))
            .build();
    buServer.start();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    buFrontend.shutdown();
    buServer.shutdown();
  }

  @Test
  public void getIdProofsInvalidSignature() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> buFrontend.getIdProofsInvalidSignature(epoch, uUrl));

    assertEquals("INVALID_ARGUMENT: Authenticity Tests Failed!", exception.getMessage());
  }
}
