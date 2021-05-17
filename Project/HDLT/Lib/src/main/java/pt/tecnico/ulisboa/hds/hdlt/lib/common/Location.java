package pt.tecnico.ulisboa.hds.hdlt.lib.common;

public class Location {

  private final Integer x;
  private final Integer y;

  public Location(Integer x, Integer y) {
    this.x = x;
    this.y = y;
  }

  public Integer getX() {
    return this.x;
  }

  public Integer getY() {
    return this.y;
  }

  public boolean isNearBy(Location location, Integer maxDistance) {
    return Math.sqrt(Math.pow(location.getX() - this.x, 2) + Math.pow(location.getY() - this.y, 2))
        < maxDistance;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Location)) return false;
    Location location = (Location) o;
    return this.x.equals(location.getX()) && this.y.equals(location.getY());
  }
}
