package ca.weblite.jdeploy.updateclient;

import java.util.UUID;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that UpdateClient uses the jdeploy launcher system properties as required: -
 * jdeploy.app.version is a prerequisite for performing any version check. -
 * jdeploy.launcher.app.version is the canonical version used for comparisons and defaults to 0.0.0.
 */
public class UpdateClientLauncherVersionTest {

  private String oldAppVersion;
  private String oldLauncherVersion;

  @Before
  public void setUp() {
    oldAppVersion = System.getProperty("jdeploy.app.version");
    oldLauncherVersion = System.getProperty("jdeploy.launcher.app.version");
  }

  @After
  public void tearDown() {
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
  }

  @Test
  public void testRequireVersionReturnsWhenAppVersionPropertyMissing() throws Exception {
    System.clearProperty("jdeploy.app.version");
    System.clearProperty("jdeploy.launcher.app.version");

    UpdateClient client = new UpdateClient();
    client.setPreferencesNodeName("/test/jdeploy/" + UUID.randomUUID());

    // Should return early without side effects
    client.requireVersion("1.2.3", new UpdateParameters.Builder("dummy").build());

    Assert.assertEquals(0L, client.getDeferUntil("dummy", ""));
    Assert.assertNull(client.getIgnoredVersion("dummy", ""));
  }

  @Test
  public void testUsesLauncherVersionOverParamsCurrentVersion() throws Exception {
    System.setProperty("jdeploy.app.version", "1.2.3");
    System.setProperty("jdeploy.launcher.app.version", "2.0.0");

    UpdateClient client = new UpdateClient();
    client.setPreferencesNodeName("/test/jdeploy/" + UUID.randomUUID());

    UpdateParameters params = new UpdateParameters.Builder("dummy").currentVersion("0.1.0").build();

    // Launcher version (2.0.0) is >= required (1.0.0) so requireVersion should return early
    client.requireVersion("1.0.0", params);

    Assert.assertEquals(0L, client.getDeferUntil("dummy", ""));
    Assert.assertNull(client.getIgnoredVersion("dummy", ""));
  }

  @Test
  public void testBranchLauncherVersionSkipsPrompt() throws Exception {
    System.setProperty("jdeploy.app.version", "1.2.3");
    System.setProperty("jdeploy.launcher.app.version", "0.0.0-featureX");

    UpdateClient client = new UpdateClient();
    client.setPreferencesNodeName("/test/jdeploy/" + UUID.randomUUID());

    // Branch versions should be treated as non-actionable and cause an early return
    client.requireVersion("1.0.0", new UpdateParameters.Builder("dummy").build());

    Assert.assertEquals(0L, client.getDeferUntil("dummy", ""));
    Assert.assertNull(client.getIgnoredVersion("dummy", ""));
  }
}
