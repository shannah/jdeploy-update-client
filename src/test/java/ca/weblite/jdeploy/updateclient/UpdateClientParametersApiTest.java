package ca.weblite.jdeploy.updateclient;

import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the parameters-based API can be exercised in headless mode and that preference-based
 * gating (ignored version) prevents further work without touching filesystem or network. The test
 * simply ensures no exceptions are thrown.
 */
public class UpdateClientParametersApiTest {

  @Test
  public void testRequireVersionWithIgnoredPreferenceDoesNotThrow() {
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
      // This should return early due to the ignored preference and not throw
      client.requireVersion(requiredVersion, params);
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail("requireVersion threw an exception: " + e.getMessage());
    }
  }
}
