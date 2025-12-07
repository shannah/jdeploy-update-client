package ca.weblite.jdeploy.updateclient;

import static org.junit.Assert.*;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for UpdateClient preference gating (Ignore / Later behavior).
 *
 * <p>These tests use a unique Preferences node per test run to avoid polluting the user's real
 * preferences.
 */
public class UpdateClientPreferencesTest {

  private UpdateClient client;
  private String prefsNodePath;

  @Before
  public void setUp() {
    client = new UpdateClient();
    // Unique preferences node for isolation
    prefsNodePath = "/test/jdeploy/updateclient/" + UUID.randomUUID().toString();
    client.setPreferencesNodeName(prefsNodePath);
  }

  @After
  public void tearDown() {
    try {
      Preferences.userRoot().node(prefsNodePath).removeNode();
    } catch (Exception ignored) {
    }
  }

  @Test
  public void testIgnorePreferenceSuppressesPromptForExactVersion() {
    String pkg = "mypkg";
    String source = "https://github.com/owner/repo";
    String ignoredVersion = "2.5.0";

    // Initially no ignored version
    assertNull(client.getIgnoredVersion(pkg, source));
    assertFalse(client.shouldSkipPrompt(pkg, source, ignoredVersion));

    // Set ignored version
    client.setIgnoredVersion(pkg, source, ignoredVersion);

    // Preference persisted and gating reports skipping for that exact version
    assertEquals(ignoredVersion, client.getIgnoredVersion(pkg, source));
    assertTrue(client.shouldSkipPrompt(pkg, source, ignoredVersion));

    // Different version should not be ignored
    assertFalse(client.shouldSkipPrompt(pkg, source, "2.4.9"));
  }

  @Test
  public void testLaterPreferenceDefersPrompts() {
    String pkg = "mypkg";
    String source = "https://github.com/owner/repo";

    // No defer set initially
    assertEquals(0L, client.getDeferUntil(pkg, source));
    assertFalse(client.shouldSkipPrompt(pkg, source, "any"));

    // Defer for 3 days from now
    long threeDaysMs = 3L * 24L * 60L * 60L * 1000L;
    long until = System.currentTimeMillis() + threeDaysMs;
    client.setDeferUntil(pkg, source, until);

    // Now prompts should be suppressed
    assertTrue(client.getDeferUntil(pkg, source) >= until);
    assertTrue(client.shouldSkipPrompt(pkg, source, "any"));

    // Move defer to the past -> prompts should no longer be suppressed
    client.setDeferUntil(pkg, source, System.currentTimeMillis() - 1000L);
    assertFalse(client.shouldSkipPrompt(pkg, source, "any"));
  }
}
