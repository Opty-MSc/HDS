package pt.tecnico.ulisboa.hds.hdlt.user.location;

import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.stream.Collectors;

public class GridManager {

  private final String uname;
  private final Map<String, Map<Integer, Location>> grid = new HashMap<>();

  public GridManager(String uname, String gridPath) throws FileNotFoundException {
    this.uname = uname;
    String[] userPosition;
    Map<Integer, Location> epochGrid;
    Scanner scanner = new Scanner(new File(gridPath));
    while (scanner.hasNextLine()) {
      userPosition = scanner.nextLine().split(";");
      epochGrid = this.grid.getOrDefault(userPosition[0].trim(), null);
      if (epochGrid == null) {
        epochGrid = new HashMap<>();
        this.grid.put(userPosition[0].trim(), epochGrid);
      }
      epochGrid.put(
          Integer.parseInt(userPosition[1].trim()),
          new Location(
              Integer.parseInt(userPosition[2].trim()), Integer.parseInt(userPosition[3].trim())));
    }
    scanner.close();
  }

  public Location getLocation(String uname, Integer epoch) {
    if (!this.grid.get(uname).containsKey(epoch)) {
      throw new UserRuntimeException("Invalid Epoch!");
    }
    return this.grid.get(uname).get(epoch);
  }

  public List<String> getCloseUsers(Integer epoch, Integer uMaxDistance) {
    Location location = this.getLocation(this.uname, epoch);
    return this.grid.keySet().stream()
        .filter(
            e ->
                !e.equals(this.uname)
                    && this.getLocation(e, epoch).isNearBy(location, uMaxDistance))
        .collect(Collectors.toList());
  }

  public Set<Integer> getEpochs(String uname) {
    return this.grid.get(uname).keySet();
  }
}
