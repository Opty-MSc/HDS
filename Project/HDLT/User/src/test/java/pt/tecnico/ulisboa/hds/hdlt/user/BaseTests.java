package pt.tecnico.ulisboa.hds.hdlt.user;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.parseURLs;

public class BaseTests {

  private static final String TEST_PROP_FILE = "/test.properties";
  protected static String myUserKSDirPath;
  protected static String myUserCrtDirPath;
  protected static String myServerCrtDirPath;
  protected static Integer myNByzantineServers;
  protected static Integer myNByzantineUsers;
  protected static Integer myMaxDistance;
  protected static String myGridPath;
  protected static Map<String, String> myUsersURLs;
  protected static Map<String, String> myServersURLs;
  protected static Integer mySessionTime;
  protected static Integer myEpochLifeTime;
  protected static Integer myPowDifficulty;
  protected static String myKeyStoreAlias;
  protected static String myKeyStorePwd;
  protected static Integer myCallTimeout;
  protected static Integer myMaxNRetries;

  @BeforeAll
  public static void oneTimeSetup() throws IOException {
    Properties testProps = new Properties();
    try {
      testProps.load(BaseTests.class.getResourceAsStream(TEST_PROP_FILE));
    } catch (IOException e) {
      final String msg = String.format("Could not load properties file %s", TEST_PROP_FILE);
      System.out.println(msg);
      throw e;
    }
    System.out.println("Properties:");
    System.out.println(testProps);

    myUserKSDirPath = testProps.getProperty("myUserKSDirPath");
    myUserCrtDirPath = testProps.getProperty("myUserCrtDirPath");
    myServerCrtDirPath = testProps.getProperty("myServerCrtDirPath");
    myNByzantineServers = Integer.parseInt(testProps.getProperty("myNByzantineServers"));
    myNByzantineUsers = Integer.parseInt(testProps.getProperty("myNByzantineUsers"));
    myMaxDistance = Integer.parseInt(testProps.getProperty("myMaxDistance"));
    myGridPath = testProps.getProperty("myGridPath");
    myUsersURLs = parseURLs(testProps.getProperty("myUsersURLsPath"), null);
    myServersURLs =
        parseURLs(testProps.getProperty("myServersURLsPath"), 3 * myNByzantineServers + 1);
    mySessionTime = Integer.parseInt(testProps.getProperty("mySessionTime"));
    myEpochLifeTime = Integer.parseInt(testProps.getProperty("myEpochLifeTime"));
    myPowDifficulty = Integer.parseInt(testProps.getProperty("myPowDifficulty"));
    myKeyStoreAlias = testProps.getProperty("myKeyStoreAlias");
    myKeyStorePwd = testProps.getProperty("myKeyStorePwd");
    myCallTimeout = Integer.parseInt(testProps.getProperty("myCallTimeout"));
    myMaxNRetries = Integer.parseInt(testProps.getProperty("myMaxNRetries"));
  }

  @AfterAll
  public static void cleanup() {}
}
