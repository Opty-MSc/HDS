package pt.tecnico.ulisboa.hds.hdlt.ha;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.tecnico.ulisboa.hds.hdlt.ha.api.ClientToServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.ha.api.HACrypto;
import pt.tecnico.ulisboa.hds.hdlt.ha.api.HAToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.ha.error.HARuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HAToServerTests extends BaseTests {
  protected static Map<String, Map<Integer, Integer[]>> grid = new HashMap<>();
  protected static Map<Integer, Map<Integer[], List<String>>> gridLocations = new HashMap<>();
  private static ClientToServerFrontend csFrontend;
  private static HAToDHServerFrontend dhFrontend;

  @BeforeAll
  public static void oneTimeSetUp() {
    String ksAlias = String.format("%s%s", myKeyStoreAlias, myUsername);
    String ksPwd = String.format("%s%s", myKeyStorePwd, myUsername);
    HACrypto crypto =
        new HACrypto(
            myHAKSPath,
            myUserCrtDirPath,
            myServerCrtDirPath,
            ksAlias,
            ksPwd,
            mySessionTime,
            myPowDifficulty);
    dhFrontend =
        new HAToDHServerFrontend(myUsername, crypto, myServersURLs, myCallTimeout, myMaxNRetries);
    csFrontend =
        new ClientToServerFrontend(
            myUsername,
            crypto,
            myServersURLs,
            myNByzantineServers,
            myNByzantineUsers,
            myMaxDistance,
            dhFrontend,
            myCallTimeout,
            myMaxNRetries);
    createGrid();
  }

  @AfterAll
  public static void cleanup() {
    dhFrontend.shutdown();
    csFrontend.shutdown();
  }

  private static void createGrid() {
    String[] userPosition;
    Map<Integer, Integer[]> epochGrid;
    Scanner scanner;
    try {
      scanner = new Scanner(new File(myGridPath));
    } catch (FileNotFoundException e) {
      return;
    }
    while (scanner.hasNextLine()) {
      userPosition = scanner.nextLine().split(";");
      epochGrid = grid.getOrDefault(userPosition[0].trim(), null);
      if (epochGrid == null) {
        epochGrid = new HashMap<>();
        grid.put(userPosition[0].trim(), epochGrid);
      }
      epochGrid.put(
          Integer.parseInt(userPosition[1].trim()),
          new Integer[] {
            Integer.parseInt(userPosition[2].trim()), Integer.parseInt(userPosition[3].trim())
          });
    }
    scanner.close();
  }

  @Test
  public void obtainUL() {
    for (String uname : grid.keySet()) {
      for (Integer epoch : grid.get(uname).keySet()) {
        Integer[] location = grid.get(uname).get(epoch);
        Location obtainUL = csFrontend.obtainUL(uname, epoch);
        assertEquals(location[0], obtainUL.getX());
        assertEquals(location[1], obtainUL.getY());
        if (!gridLocations.containsKey(epoch)) gridLocations.put(epoch, new HashMap<>());
        if (!gridLocations.get(epoch).containsKey(location))
          gridLocations.get(epoch).put(location, new ArrayList<>());
        gridLocations.get(epoch).get(location).add(uname);
      }
    }
  }

  @Test
  public void obtainULNotPresent() {
    HARuntimeException exception =
        assertThrows(HARuntimeException.class, () -> csFrontend.obtainUL("NonExistent", 0));
    assertEquals("NOT_FOUND: No Report Found!", exception.getMessage());
  }

  @Test
  public void obtainUAtL() {
    for (Integer epoch : gridLocations.keySet()) {
      for (Integer[] nearLocation : gridLocations.get(epoch).keySet()) {
        List<String> unames =
            csFrontend.obtainUAtL(epoch, new Location(nearLocation[0], nearLocation[1]));
        for (String uname : unames) {
          Integer[] location = grid.get(uname).get(epoch);
          assertTrue(
              Math.abs(location[0] - nearLocation[0]) <= 2
                  && Math.abs(location[1] - nearLocation[1]) <= 2);
        }
      }
    }
  }
}
