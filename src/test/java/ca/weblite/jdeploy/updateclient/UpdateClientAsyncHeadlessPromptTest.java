package ca.weblite.jdeploy.updateclient;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that in headless mode the async requireVersion flow defaults to "Later" (defer)
 * and persists the defer-until preference before the returned future completes.
 *
 * <p>This test avoids network access by creating a local file-based "npm registry" directory
 * and pointing the updater to it via the NPM_REGISTRY_URL environment variable (set via
 * reflection for the test process).
 */
public class UpdateClientAsyncHeadlessPromptTest {

  private String oldAppVersion;
  private String oldLauncherVersion;
  private String oldHeadless;
  private String prefsNodePath;
  private Path tempRegistryDir;
  private String oldNpmRegistryEnvValue;

  @Before
  public void setUp() throws Exception {
    oldAppVersion = System.getProperty("jdeploy.app.version");
    oldLauncherVersion = System.getProperty("jdeploy.launcher.app.version");
    oldHeadless = System.getProperty("java.awt.headless");
    prefsNodePath = null;
    tempRegistryDir = null;
    // Capture existing NPM_REGISTRY_URL if present so we can restore it later
    oldNpmRegistryEnvValue = System.getenv("NPM_REGISTRY_URL");
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

    // Restore environment variable (best-effort)
    try {
      if (oldNpmRegistryEnvValue != null) {
        setEnv("NPM_REGISTRY_URL", oldNpmRegistryEnvValue);
      } else {
        removeEnv("NPM_REGISTRY_URL");
      }
    } catch (Exception ignored) {
    }

    // Remove preferences node if created
    if (prefsNodePath != null) {
      try {
        Preferences.userRoot().node(prefsNodePath).removeNode();
      } catch (Exception ignored) {
      }
    }

    // Delete temporary registry dir if created
    if (tempRegistryDir != null) {
      try {
        Files.walk(tempRegistryDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                  }
                });
      } catch (IOException ignored) {
      }
    }
  }

  @Test
  public void testRequireVersionAsyncHeadlessDefaultsToLaterAndPersistsDefer() throws Exception {
    // Ensure headless to force promptForUpdate to return LATER without showing UI
    System.setProperty("java.awt.headless", "true");

    // Set app/launcher versions such that an update is required (launcher < required)
    System.setProperty("jdeploy.app.version", "1.0.0");
    System.setProperty("jdeploy.launcher.app.version", "1.0.0");

    UpdateClient client = new UpdateClient();

    // Unique preferences node to avoid colliding with other tests or user prefs
    prefsNodePath = "/test/jdeploy/updateclient/" + UUID.randomUUID();
    client.setPreferencesNodeName(prefsNodePath);

    String packageName = "headless-test-pkg";
    String source = ""; // npm-hosted semantics
    String requiredVersion = "2.0.0";

    // Create a minimal local "npm registry" directory with a package-info file so the updater
    // can find a latest version without network access.
    tempRegistryDir = Files.createTempDirectory("jdeploy-test-npm-registry-");
    // Ensure path ends with a slash when used as registry base
    String registryBase = tempRegistryDir.toUri().toString();
    if (!registryBase.endsWith("/")) {
      registryBase = registryBase + "/";
    }

    // Minimal package-info JSON: versions object and dist-tags.latest pointing to 2.0.0
    String packageInfoJson =
        "{\n"
            + "  \"dist-tags\": { \"latest\": \"2.0.0\" },\n"
            + "  \"versions\": { \"1.0.0\": {}, \"2.0.0\": {} }\n"
            + "}\n";

    // Create a file named after the package under the registry directory
    Path pkgFile = tempRegistryDir.resolve(packageName);
    Files.write(pkgFile, packageInfoJson.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

    // Point the updater to our file:// based registry via environment variable (set via reflection)
    setEnv("NPM_REGISTRY_URL", registryBase);

    UpdateParameters params =
        new UpdateParameters.Builder(packageName).source(source).appTitle("Headless Test").build();

    long before = System.currentTimeMillis();

    UpdateClient.UpdateResult result = client.requireVersionAsync(requiredVersion, params).get();

    assertNotNull("UpdateResult should not be null", result);
    // In headless mode, promptForUpdate() returns LATER and that should result in not required
    assertFalse("Headless prompt should default to LATER (not required)", result.isRequired());

    // Verify that deferUntil preference was set to a timestamp in the future (>= before)
    long deferUntil = client.getDeferUntil(packageName, source == null ? "" : source);
    assertTrue("Defer-until should be set to a timestamp in the future", deferUntil >= before);
  }

  // --- environment variable helpers (reflection) ---
  @SuppressWarnings("unchecked")
  private static void setEnv(String key, String value) {
    try {
      // Try the ProcessEnvironment approach first (most Oracle/OpenJDK)
      Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
      try {
        Field theEnvironmentField = pe.getDeclaredField("theEnvironment");
        theEnvironmentField.setAccessible(true);
        try {
          @SuppressWarnings("unchecked")
          Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
          env.put(key, value);
        } catch (IllegalAccessException ignored) {
          // best-effort; ignore if we cannot access the field
        }
      } catch (NoSuchFieldException ignored) {
        // ignore and try fallback
      }
      try {
        Field theCaseInsensitiveEnvironmentField = pe.getDeclaredField("theCaseInsensitiveEnvironment");
        theCaseInsensitiveEnvironmentField.setAccessible(true);
        try {
          @SuppressWarnings("unchecked")
          Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
          cienv.put(key, value);
        } catch (IllegalAccessException ignored) {
          // best-effort; ignore if we cannot access the field
        }
      } catch (NoSuchFieldException ignored) {
        // ignore
      }
    } catch (ClassNotFoundException ignored) {
      // ignore
    }

    // Fallback: try modifying the underlying map of System.getenv()
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field m = cl.getDeclaredField("m");
      m.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> modifiable = (Map<String, String>) m.get(env);
      modifiable.put(key, value);
    } catch (Exception ignored) {
      // best-effort; if we cannot set the environment variable, tests may fail.
      // Do not rethrow to allow the tearDown to attempt cleanup.
    }
  }

  @SuppressWarnings("unchecked")
  private static void removeEnv(String key) {
    try {
      Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
      try {
        Field theEnvironmentField = pe.getDeclaredField("theEnvironment");
        theEnvironmentField.setAccessible(true);
        try {
          @SuppressWarnings("unchecked")
          Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
          env.remove(key);
        } catch (IllegalAccessException ignored) {
          // best-effort; ignore if we cannot access the field
        }
      } catch (NoSuchFieldException ignored) {
      }
      try {
        Field theCaseInsensitiveEnvironmentField = pe.getDeclaredField("theCaseInsensitiveEnvironment");
        theCaseInsensitiveEnvironmentField.setAccessible(true);
        try {
          @SuppressWarnings("unchecked")
          Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
          cienv.remove(key);
        } catch (IllegalAccessException ignored) {
          // best-effort; ignore if we cannot access the field
        }
      } catch (NoSuchFieldException ignored) {
      }
    } catch (ClassNotFoundException ignored) {
    }

    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field m = cl.getDeclaredField("m");
      m.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> modifiable = (Map<String, String>) m.get(env);
      modifiable.remove(key);
    } catch (Exception ignored) {
    }
  }
}
