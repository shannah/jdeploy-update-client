package ca.weblite.jdeploy.updateclient;

import static org.junit.Assert.*;

import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the asynchronous requireVersionAsync API to ensure it can be used in headless mode and that
 * preference-based gating (ignored version) prevents further work without touching filesystem or
 * network. The test simply ensures no exceptions are thrown and the UpdateResult indicates no
 * installer launch is required when the version is ignored.
 */
public class UpdateClientAsyncApiTest {

  @Test
  public void testRequireVersionAsyncWithIgnoredPreferenceDoesNotThrow() {
    // Ensure headless to avoid any UI interaction during test
    System.setProperty("java.awt.headless", "true");

    UpdateClient client = new UpdateClient();
    // Use a unique preferences node so tests don't interfere with real user prefs
    client.setPreferencesNodeName("/test/jdeploy/updateclient/" + UUID.randomUUID());

    String packageName = "dummy-pkg";
    String source = ""; // empty = npm-hosted in our semantics
    String requiredVersion = "2.0.0";

    // Prepare parameters: currentVersion < requiredVersion so we would normally proceed,
    // but we will mark the requiredVersion as ignored to exercise preference gating.
    UpdateParameters params =
        new UpdateParameters.Builder(packageName)
            .source(source)
            .appTitle("Dummy App")
            .currentVersion("1.0.0")
            .build();

    // Mark this specific requiredVersion as ignored for the package+source
    client.setIgnoredVersion(packageName, source, requiredVersion);

    try {
      UpdateClient.UpdateResult result =
          client.requireVersionAsync(requiredVersion, params).get();
      // Because we ignored the requiredVersion earlier, the async result should indicate no launch required.
      Assert.assertNotNull(result);
      assertFalse(result.isRequired());
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail("requireVersionAsync threw an exception: " + e.getMessage());
    }
  }
}
