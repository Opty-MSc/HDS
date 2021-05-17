package pt.tecnico.ulisboa.hds.hdlt.server.repository;

import io.grpc.Status;
import org.postgresql.util.PSQLException;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBAuthProof;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBProof;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBReport;

import java.math.BigInteger;
import java.sql.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.postgresql.util.PSQLState.UNIQUE_VIOLATION;

public class DBManager {
  private static final int LOCATION_RANGE = 2;
  private final String dbUrl;
  private final String dbUser;
  private final String dbPwd;
  private final ServerCrypto sCrypto;

  public DBManager(String dbName, String dbUser, String dbPwd, ServerCrypto sCrypto) {
    this.sCrypto = sCrypto;
    this.dbUrl = String.format("jdbc:postgresql://localhost:5432/%s", dbName);
    this.dbUser = dbUser.toLowerCase();
    this.dbPwd = dbPwd;
    try {
      DBPopulate.createTables(this.newConnection());
      InMemoryDB.addAllNonces(this.getNonces());
    } catch (SQLException e) {
      throw new AssertError(DBManager.class.getSimpleName(), "Constructor", e);
    }
  }

  private Connection newConnection() {
    try {
      return DriverManager.getConnection(this.dbUrl, dbUser, dbPwd);
    } catch (SQLException e) {
      throw new AssertError(DBManager.class.getSimpleName(), "newConnection", e);
    }
  }

  public void addNonce(BigInteger nonce) {
    try (Connection connection = this.newConnection();
        PreparedStatement stmt = connection.prepareStatement(DBPopulate.InsertNonceSQL)) {
      stmt.setBytes(1, nonce.toByteArray());
      stmt.executeUpdate();
    } catch (SQLException e) {
      if (e instanceof PSQLException && e.getSQLState().equals(UNIQUE_VIOLATION.getState())) {
        throw new ServerStatusRuntimeException(
            this.sCrypto, Status.ALREADY_EXISTS, "Nonce Already Exists!", nonce);
      } else {
        throw new ServerStatusRuntimeException(
            this.sCrypto, Status.INTERNAL, "Database Error!", nonce);
      }
    }
  }

  public Set<BigInteger> getNonces() throws SQLException {
    Set<BigInteger> nonces = new HashSet<>();
    Connection connection = this.newConnection();
    PreparedStatement stmt = connection.prepareStatement(DBPopulate.SelectNoncesSQL);
    ResultSet rs = stmt.executeQuery();
    while (rs.next()) nonces.add(new BigInteger(1, rs.getBytes("nonce")));
    stmt.close();
    connection.close();
    return nonces;
  }

  public boolean hasReport(String uname, int epoch) throws SQLException {
    Connection connection = this.newConnection();
    PreparedStatement stmt = connection.prepareStatement(DBPopulate.SelectHasUserReportSQL);
    stmt.setString(1, uname);
    stmt.setInt(2, epoch);
    ResultSet rs = stmt.executeQuery();
    boolean hasReport = rs.next();
    stmt.close();
    connection.close();
    return hasReport;
  }

  public void addReport(
      String uname,
      int epoch,
      Location location,
      Map<String, byte[]> uIdProofs,
      Map<String, Map<String, byte[]>> sIdProofs)
      throws SQLException {
    Connection connection = this.newConnection();
    try {
      PreparedStatement stmt = connection.prepareStatement(DBPopulate.InsertUserReportSQL);
      connection.setAutoCommit(false);
      stmt.setString(1, uname);
      stmt.setInt(2, epoch);
      stmt.setInt(3, location.getX());
      stmt.setInt(4, location.getY());
      stmt.executeUpdate();
      stmt.close();
      for (Map.Entry<String, byte[]> uIdProof : uIdProofs.entrySet()) {
        this.addUserLocationProof(connection, uname, epoch, uIdProof.getKey(), uIdProof.getValue());
        for (Map.Entry<String, byte[]> sIdProof : sIdProofs.get(uIdProof.getKey()).entrySet()) {
          this.addServerLocationProof(
              connection, uname, epoch, uIdProof.getKey(), sIdProof.getKey(), sIdProof.getValue());
        }
      }
      connection.commit();
    } catch (SQLException e) {
      connection.rollback();
      throw e;
    }
    connection.close();
  }

  public void addUserLocationProof(
      Connection connection, String uname, int epoch, String uSigner, byte[] uSignedProof)
      throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(DBPopulate.InsertUserLocationProofSQL);
    stmt.setString(1, uname);
    stmt.setInt(2, epoch);
    stmt.setString(3, uSigner);
    stmt.setBytes(4, uSignedProof);
    stmt.executeUpdate();
    stmt.close();
  }

  public void addServerLocationProof(
      Connection connection,
      String uname,
      int epoch,
      String uSigner,
      String sSigner,
      byte[] sSignedProof)
      throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(DBPopulate.InsertServerLocationProofSQL);
    stmt.setString(1, uname);
    stmt.setInt(2, epoch);
    stmt.setString(3, uSigner);
    stmt.setString(4, sSigner);
    stmt.setBytes(5, sSignedProof);
    stmt.executeUpdate();
    stmt.close();
  }

  public DBReport getReport(String uname, int epoch, BigInteger nonce) {
    try (Connection connection = this.newConnection();
        PreparedStatement stmt = connection.prepareStatement(DBPopulate.SelectUserReportSQL)) {
      stmt.setString(1, uname);
      stmt.setInt(2, epoch);
      ResultSet rs = stmt.executeQuery();
      if (!rs.next()) {
        throw new ServerStatusRuntimeException(
            this.sCrypto, Status.NOT_FOUND, "No Report Found!", nonce);
      }
      int x = rs.getInt("x");
      int y = rs.getInt("y");
      DBReport report = new DBReport(new Location(x, y));
      do {
        String uSigner = rs.getString("uSigner");
        String sSigner = rs.getString("sSigner");
        byte[] uSignedProof = rs.getBytes("uSignedProof");
        byte[] sSignedProof = rs.getBytes("sSignedProof");

        report.putUserIdProof(uSigner, uSignedProof);
        report.putServerIdProof(uSigner, sSigner, sSignedProof);
      } while (rs.next());
      return report;
    } catch (SQLException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, Status.INTERNAL, "Database Error!", nonce);
    }
  }

  public Map<String, DBReport> getReportsAtLocation(
      int epoch, Location location, BigInteger nonce) {
    try (Connection connection = this.newConnection();
        PreparedStatement stmt =
            connection.prepareStatement(DBPopulate.SelectUserReportsByLocationSQL)) {
      stmt.setInt(1, epoch);
      stmt.setInt(2, location.getX() + LOCATION_RANGE);
      stmt.setInt(3, location.getX() - LOCATION_RANGE);
      stmt.setInt(4, location.getY() + LOCATION_RANGE);
      stmt.setInt(5, location.getY() - LOCATION_RANGE);
      ResultSet rs = stmt.executeQuery();
      Map<String, DBReport> reports = new HashMap<>();
      while (rs.next()) {
        String uname = rs.getString("uname");
        int x = rs.getInt("x");
        int y = rs.getInt("y");
        String uSigner = rs.getString("uSigner");
        String sSigner = rs.getString("sSigner");
        byte[] uSignedProof = rs.getBytes("uSignedProof");
        byte[] sSignedProof = rs.getBytes("sSignedProof");

        if (!reports.containsKey(uname)) {
          reports.put(uname, new DBReport(new Location(x, y)));
        }
        reports.get(uname).putUserIdProof(uSigner, uSignedProof);
        reports.get(uname).putServerIdProof(uSigner, sSigner, sSignedProof);
      }
      return reports;
    } catch (SQLException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, Status.INTERNAL, "Database Error!", nonce);
    }
  }

  public Set<DBAuthProof> getLocationProofs(String uSigner, Object[] epochs, BigInteger nonce) {
    try (Connection connection = this.newConnection();
        PreparedStatement stmt = connection.prepareStatement(DBPopulate.SelectLocationProofsSQL)) {
      stmt.setString(1, uSigner);
      stmt.setArray(2, connection.createArrayOf("INTEGER", epochs));
      ResultSet rs = stmt.executeQuery();
      Map<String, Map<Integer, DBAuthProof>> authProofs = new HashMap<>();
      while (rs.next()) {
        String uname = rs.getString("uname");
        int epoch = rs.getInt("epoch");
        int x = rs.getInt("x");
        int y = rs.getInt("y");
        String sSigner = rs.getString("sSigner");
        byte[] uSignedProof = rs.getBytes("uSignedProof");
        byte[] sSignedProof = rs.getBytes("sSignedProof");

        if (!authProofs.containsKey(uname) || !authProofs.get(uname).containsKey(epoch)) {
          authProofs.putIfAbsent(uname, new HashMap<>());
          DBProof proof = new DBProof(uname, epoch, new Location(x, y));
          authProofs.get(uname).put(epoch, new DBAuthProof(proof, uSignedProof));
        }
        authProofs.get(uname).get(epoch).putServerIdProof(sSigner, sSignedProof);
      }
      return authProofs.values().stream()
          .flatMap(e -> e.values().stream())
          .collect(Collectors.toSet());
    } catch (SQLException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, Status.INTERNAL, "Database Error!", nonce);
    }
  }
}
