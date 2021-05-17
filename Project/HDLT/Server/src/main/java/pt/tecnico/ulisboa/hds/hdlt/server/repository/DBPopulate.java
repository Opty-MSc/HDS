package pt.tecnico.ulisboa.hds.hdlt.server.repository;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DBPopulate {

  @Language("SQL")
  public static final String InsertNonceSQL = "INSERT INTO UserNonces (nonce) VALUES (?);";

  @Language("SQL")
  public static final String InsertUserReportSQL =
      "INSERT INTO UserReports (uname, epoch, x, y) VALUES (?, ?, ?, ?);";

  @Language("SQL")
  public static final String InsertUserLocationProofSQL =
      "INSERT INTO UserLocationProofs (uname, epoch, uSigner, uSignedProof) VALUES (?, ?, ?, ?);";

  @Language("SQL")
  public static final String InsertServerLocationProofSQL =
      "INSERT INTO ServerLocationProofs (uname, epoch, uSigner, sSigner, sSignedProof) VALUES (?, ?, ?, ?, ?);";

  @Language("SQL")
  public static final String SelectNoncesSQL = "SELECT * FROM UserNonces;";

  @Language("SQL")
  public static final String SelectHasUserReportSQL =
      "SELECT * FROM UserReports WHERE uname = ? AND epoch = ?;";

  @Language("SQL")
  public static final String SelectUserReportSQL =
      "SELECT * FROM UserReports NATURAL JOIN UserLocationProofs NATURAL JOIN ServerLocationProofs WHERE uname = ? AND epoch = ?;";

  @Language("SQL")
  public static final String SelectUserReportsByLocationSQL =
      "SELECT * FROM UserReports NATURAL JOIN UserLocationProofs NATURAL JOIN ServerLocationProofs WHERE epoch = ? AND x < ? AND x > ? AND y < ? AND y > ?;";

  @Language("SQL")
  public static final String SelectLocationProofsSQL =
      "SELECT * FROM UserReports NATURAL JOIN UserLocationProofs NATURAL JOIN ServerLocationProofs WHERE uSigner = ? AND epoch = ANY(?::INTEGER[]);";

  public static void createTables(Connection connection) throws SQLException {
    Statement stmt = connection.createStatement();
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS UserNonces("
            + "nonce BYTEA NOT NULL,"
            + "PRIMARY KEY (nonce));");
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS UserReports("
            + "uname VARCHAR(20) NOT NULL,"
            + "epoch INTEGER NOT NULL,"
            + "x INTEGER NOT NULL,"
            + "y INTEGER NOT NULL,"
            + "PRIMARY KEY (uname, epoch));");
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS UserLocationProofs("
            + "uname VARCHAR(20) NOT NULL,"
            + "epoch INTEGER NOT NULL,"
            + "uSigner VARCHAR(20) NOT NULL,"
            + "uSignedProof BYTEA NOT NULL,"
            + "FOREIGN KEY(uname, epoch) REFERENCES UserReports(uname, epoch),"
            + "PRIMARY KEY (uname, epoch, uSigner));");
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS ServerLocationProofs("
            + "uname VARCHAR(20) NOT NULL,"
            + "epoch INTEGER NOT NULL,"
            + "uSigner VARCHAR(20) NOT NULL,"
            + "sSigner VARCHAR(20) NOT NULL,"
            + "sSignedProof BYTEA NOT NULL,"
            + "FOREIGN KEY(uname, epoch, uSigner) REFERENCES UserLocationProofs(uname, epoch, uSigner),"
            + "PRIMARY KEY (uname, epoch, uSigner, sSigner));");
    stmt.close();
  }
}
