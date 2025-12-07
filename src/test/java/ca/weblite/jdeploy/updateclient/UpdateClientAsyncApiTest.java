package ca.weblite.jdeploy.updateclient;

import static org.junit.Assert.*;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the asynchronous requireVersionAsync API to ensure it behaves as expected under various
 * launcher / preferences configurations.
 */
public class UpdateClientAsyncApiTest {

  private String oldAppVersion;
  private String oldLauncherVersion;
  private String oldHeadless;
  private String prefsNodePath;

  @Before
  public void setUp() {
    oldAppVersion = System.getProperty("jdeploy.app.version");
    oldLauncherVersion = System.getProperty("jdeploy.launcher.app.version");
    oldHeadless = System.getProperty("java.awt.headless");
    prefsNodePath = null;
  }

  @After
  public void tearDown() {
    // Restore system properties
    if (oldAppVersion != null) {
      System.setProperty("jdeploy.app.version", oldAppVersion);
    } else {
      System.clearProperty("jdeploy.app.version");
    }

    if (oldLauncherVersion != null) {
      System.setProperty("jdeploy.launcher.app.version", oldLauncherVersion);
    } else {
      System.clearProperty("jdeploy.launcher.app.version");
    }

    if (oldHeadless != null) {
      System.setProperty("java.awt.headless", oldHeadless);
    } else {
      System.clearProperty("java.awt.headless");
    }

    // Attempt to remove test preferences node if created
    if (prefsNodePath != null) {
      try {
        Preferences.userRoot().node(prefsNodePath).removeNode();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  public void testRequireVersionAsyncReturnsNotRequiredWhenAppVersionPropertyMissing()
      throws Exception {
    System.clearProperty("jdeploy.app.version");
    System.clearProperty("jdeploy.launcher.app.version");

    UpdateClient client = new UpdateClient();
    prefsNodePath = "/test/jdeploy/" + UUID.randomUUID();
    client.setPreferencesNodeName(prefsNodePath);

    UpdateParameters params = new UpdateParameters.Builder("dummy").build();

    UpdateClient.UpdateResult result =
        client.requireVersionAsync("1.2.3", params).get();

    assertNotNull(result);
    assertFalse("When jdeploy.app.version is missing, async check should not require update",
        result.isRequired());
  }

  @Test
  public void testRequireVersionAsyncUsesLauncherVersionOverParamsVersion() throws Exception {
    System.setProperty("jdeploy.app.version", "1.2.3");
    System.setProperty("jdeploy.launcher.app.version", "2.0.0");

    UpdateClient client = new UpdateClient();
    prefsNodePath = "/test/jdeploy/" + UUID.randomUUID();
    client.setPreferencesNodeName(prefsNodePath);

    UpdateParameters params =
        new UpdateParameters.Builder("dummy").currentVersion("0.1.0").build();

    UpdateClient.UpdateResult result =
        client.requireVersionAsync("1.0.0", params).get();

    assertNotNull(result);
    assertFalse("Launcher version >= required should result in no required update", result.isRequired());
  }

  @Test
  public void testRequireVersionAsyncBranchLauncherVersionSkips() throws Exception {
    System.setProperty("jdeploy.app.version", "1.2.3");
    System.setProperty("jdeploy.launcher.app.version", "0.0.0-featureX");

    UpdateClient client = new UpdateClient();
    prefsNodePath = "/test/jdeploy/" + UUID.randomUUID();
    client.setPreferencesNodeName(prefsNodePath);

    UpdateParameters params = new UpdateParameters.Builder("dummy").build();

    UpdateClient.UpdateResult result =
        client.requireVersionAsync("1.0.0", params).get();

    assertNotNull(result);
    assertFalse("Branch launcher versions should skip update prompts / requirements", result.isRequired());
  }

  @Test
  public void testRequireVersionAsyncRespectsIgnoredPreference() throws Exception {
    // Ensure headless to avoid UI interactions in any fallback flows
    System.setProperty("java.awt.headless", "true");

    System.setProperty("jdeploy.app.version", "1.0.0");
    System.setProperty("jdeploy.launcher.app.version", "1.0.0");

    UpdateClient client = new UpdateClient();
    prefsNodePath = "/test/jdeploy/updateclient/" + UUID.randomUUID();
    client.setPreferencesNodeName(prefsNodePath);

    String packageName = "dummy-pkg";
    String source = ""; // empty = npm-hosted in our semantics
    String requiredVersion = "2.0.0";

    UpdateParameters params =
        new UpdateParameters.Builder(packageName)
            .source(source)
            .appTitle("Dummy App")
            .currentVersion("1.0.0")
            .build();

    // Mark this specific requiredVersion as ignored for the package+source
    client.setIgnoredVersion(packageName, source, requiredVersion);

    UpdateClient.UpdateResult result =
        client.requireVersionAsync(requiredVersion, params).get();

    assertNotNull(result);
    assertFalse("Ignored requiredVersion should lead to isRequired() == false", result.isRequired());
  }
}
