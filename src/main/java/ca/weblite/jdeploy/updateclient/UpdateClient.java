package ca.weblite.jdeploy.updateclient;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import javax.swing.*;

/**
 * Client for checking and installing application updates.
 *
 * <p>The updater exposes an async-first API for checking whether an application update is
 * required and for launching the installer. Callers are encouraged to use {@link
 * #requireVersionAsync(String, UpdateParameters)} which performs the check asynchronously and
 * returns an {@link UpdateResult} that the caller can inspect and act upon. The returned future
 * completes only after the update decision has been resolved: the user has been prompted and has
 * selected one of the available choices (Update Now, Later, or Ignore). When running in a
 * headless environment the prompt is skipped and a conservative default (defer/Later) is chosen so
 * no UI is shown.
 *
 * <p>This design decouples the network/IO-based check from installer launch and JVM shutdown so
 * callers can perform cleanup (for example, saving user data, flushing logs, releasing resources)
 * before launching the installer and optionally exiting the process. If the user explicitly
 * selects "Update Now", the returned {@link UpdateResult} will report {@code isRequired() == true}
 * and callers may invoke {@link UpdateResult#launchInstaller()} to download and launch the
 * installer. Important: {@link UpdateResult#launchInstaller()} does NOT call {@code
 * System.exit(0)} — callers are responsible for performing any necessary cleanup and exiting the
 * JVM if desired.
 *
 * <p>Supports one workflow:
 *
 * <ol>
 *   <li>Parameters-based workflow (preferred): callers construct and supply an {@link
 *       UpdateParameters} instance to {@link #requireVersionAsync(String, UpdateParameters)}. The
 *       parameters-based overload requires no local project files (such as {@code package.json})
 *       and is suitable for bundled, sandboxed, or otherwise restricted environments where
 *       filesystem access is unavailable or undesirable.
 * </ol>
 *
 * <p>Notes on the {@code source} field in {@link UpdateParameters}:
 *
 * <ul>
 *   <li><b>GitHub-hosted packages:</b> set {@code source} to the repository URL (for example,
 *       {@code https://github.com/Owner/Repo}). The updater will attempt to download package-info
 *       files from GitHub releases (with a fallback to the secondary package-info file name).
 *   <li><b>npm-hosted packages:</b> leave {@code source} null or empty to instruct the updater to
 *       use the npm registry workflow (the registry URL can be overridden via the {@code
 *       NPM_REGISTRY_URL} environment variable).
 * </ul>
 *
 * <p>When using the parameters-based overload, callers should supply at least {@code packageName}.
 * Optionally provide {@code currentVersion} (the updater will otherwise consult the {@code
 * jdeploy.app.version} system property to preserve legacy behaviour). The {@code appTitle} can be
 * supplied to improve UI prompts; if omitted UIs should fall back to {@code packageName}.
 *
 * <p>Example usage (async-first, caller-controlled installer launch and JVM exit):
 *
 * <pre>{@code
 * // Preferred: check asynchronously, then if update required, launch the installer,
 * // do any required cleanup (save state, flush logs, etc.) and then exit.
 * client.requireVersionAsync("2.0.0", params)
 *       .thenApply(result -> {
 *           if (result.isRequired()) {
 *               try {
 *                   result.launchInstaller();
 *                   // perform cleanup (save state, flush logs, etc.)
 *                   System.exit(0);
 *               } catch (IOException e) {
 *                   // handle download/launch error as appropriate
 *               }
 *           }
 *           return result;
 *       });
 * }</pre>
 *
 * <p>When using the async API the {@link UpdateResult#launchInstaller()} method does NOT call
 * {@code System.exit(0)}. Callers should perform any necessary cleanup and then exit the JVM if
 * desired. The async pattern avoids forcing shutdown from library code and allows applications to
 * preserve data or flush logs before terminating.
 */
public class UpdateClient {

  // Defer period in days when user chooses "Later"
  private static final int DEFAULT_DEFER_DAYS = 7;

  private enum UpdateDecision {
    UPDATE_NOW,
    LATER,
    IGNORE
  }

  /**
   * Asynchronous version of requireVersion.
   *
   * <p>Returns a {@link java.util.concurrent.CompletableFuture} that completes with an {@link
   * UpdateResult} describing the outcome of the check. The returned {@link UpdateResult} will
   * indicate whether an update is required via {@link UpdateResult#isRequired()}.
   *
   * <p>Behavioral note: the returned {@link CompletableFuture} completes only after the update
   * decision has been resolved. In normal (non-headless) environments the user will be shown a UI
   * prompt and must select one of the options: Update Now, Later, or Ignore. The future completes
   * after the user makes that choice and the corresponding preference (ignore/defer) is persisted.
   * When running in a headless environment the prompt is skipped and a conservative default (defer /
   * Later) is used and the future completes immediately with the appropriate {@link UpdateResult}.
   *
   * <p>If the user selects "Update Now" then the returned {@link UpdateResult} will have {@code
   * isRequired() == true}. Callers may then invoke {@link UpdateResult#launchInstaller()} to
   * download and launch the installer. Important: {@link UpdateResult#launchInstaller()} does NOT
   * call {@code System.exit(0)}; callers are responsible for performing any necessary cleanup and
   * exiting the JVM if desired.
   *
   * <p>The method preserves previous gating semantics: {@code jdeploy.app.version} must be set for
   * checks to proceed, the launcher-reported version ({@code jdeploy.launcher.app.version}) is used
   * as the canonical current version for comparisons, and preferences are consulted for ignore/defer
   * behavior.
   *
   * <p>Any IO errors will complete the future exceptionally with an {@link IOException}.
   *
   * <p>Example usage showing an inline handler that launches the installer and then exits:
   *
   * <pre>{@code
   * client.requireVersionAsync("2.0.0", params)
   *       .thenApply(result -> {
   *           if (result.isRequired()) {
   *               try {
   *                   result.launchInstaller();
   *                   // perform cleanup (save state, flush logs, etc.)
   *                   System.exit(0);
   *               } catch (IOException e) {
   *                   // handle download/launch failure as appropriate
   *               }
   *           }
   *           return result;
   *       });
   * }</pre>
   *
   * <p>Behavior summary:
   * <ul>
   *   <li>The returned future completes only after the user has been prompted (except in headless mode)
   *       and has made a choice (Update Now, Later, or Ignore).</li>
   *   <li>If the user chose Update Now then {@link UpdateResult#isRequired()} will be {@code true}
   *       and callers may invoke {@link UpdateResult#launchInstaller()}.</li>
   *   <li>{@link UpdateResult#launchInstaller()} does NOT call {@code System.exit(0)}; callers are
   *       responsible for any cleanup and for exiting the JVM if desired.</li>
   * </ul>
   *
   * @param requiredVersion the required version string
   * @param params parameters describing the application (packageName is required)
   * @return CompletableFuture completing with UpdateResult
   */
  public CompletableFuture<UpdateResult> requireVersionAsync(
      final String requiredVersion, final UpdateParameters params) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            if (requiredVersion == null || requiredVersion.isEmpty()) {
              return new UpdateResult(this, null, null, null, null, false);
            }
            if (params == null) {
              throw new IllegalArgumentException("params must not be null");
            }

            // Prerequisite: the app must be running under the jdeploy launcher.
            // If jdeploy.app.version is not set, do not perform update checks.
            String appVersionProperty = System.getProperty("jdeploy.app.version");
            if (appVersionProperty == null || appVersionProperty.isEmpty()) {
              // Not running via jdeploy launcher; preserve legacy behaviour by doing nothing.
              return new UpdateResult(this, params.getPackageName(), params.getSource(), null,
                  requiredVersion, false);
            }

            // Use the launcher's reported app version for comparisons. Default to "0.0.0" if missing.
            String launcherVersion = System.getProperty("jdeploy.launcher.app.version");
            if (launcherVersion == null || launcherVersion.isEmpty()) {
              launcherVersion = "0.0.0";
            }

            // Use launcherVersion as the canonical currentVersion for later logic.
            String currentVersion = launcherVersion;

            // If branch version or already >= requiredVersion, return early (no update required).
            if (isBranchVersion(currentVersion) || compareVersion(currentVersion, requiredVersion) >= 0) {
              return new UpdateResult(this, params.getPackageName(), params.getSource(), currentVersion,
                  requiredVersion, false);
            }

            String packageName = params.getPackageName();
            String source = params.getSource() == null ? "" : params.getSource();

            // Respect early preference gating (ignore / defer). If gating says skip, return not required.
            if (shouldSkipPrompt(packageName, source, requiredVersion)) {
              return new UpdateResult(this, packageName, source, currentVersion, requiredVersion, false);
            }

            // Determine whether to include prereleases in the lookup.
            boolean isPrerelease = "true".equals(System.getProperty("jdeploy.prerelease", "false"));

            // Fetch latest version (network). Any IO error will be wrapped and complete exceptionally.
            String latestVersion = findLatestVersion(packageName, source, isPrerelease);

            // If the latest version was already explicitly ignored, do not require update.
            String ignoredVersion = getIgnoredVersion(packageName, source);
            if (ignoredVersion != null && !ignoredVersion.isEmpty() && ignoredVersion.equals(latestVersion)) {
              return new UpdateResult(this, packageName, source, currentVersion, requiredVersion, false, latestVersion);
            }

            // At this point, we have a candidate latestVersion and the launcher is older than requiredVersion.
            // Prompt the user to decide whether to update now, later, or ignore this version.
            // promptForUpdate handles headless mode and EDT invocation.
            UpdateDecision decision =
                promptForUpdate(packageName, params.getAppTitle(), currentVersion, requiredVersion);

            switch (decision) {
              case IGNORE:
                // Persist the ignored version for this package+source and return not required.
                setIgnoredVersion(packageName, source, requiredVersion);
                return new UpdateResult(
                    this, packageName, source, currentVersion, requiredVersion, false, latestVersion);
              case LATER:
                // Defer prompts for DEFAULT_DEFER_DAYS days.
                long until =
                    System.currentTimeMillis()
                        + java.util.concurrent.TimeUnit.DAYS.toMillis(DEFAULT_DEFER_DAYS);
                setDeferUntil(packageName, source, until);
                return new UpdateResult(
                    this, packageName, source, currentVersion, requiredVersion, false, latestVersion);
              case UPDATE_NOW:
                // Caller is responsible for launching the installer and exiting the JVM.
                return new UpdateResult(
                    this, packageName, source, currentVersion, requiredVersion, true, latestVersion);
              default:
                // Defensive: treat unknown result as defer (LATER)
                long defUntil =
                    System.currentTimeMillis()
                        + java.util.concurrent.TimeUnit.DAYS.toMillis(DEFAULT_DEFER_DAYS);
                setDeferUntil(packageName, source, defUntil);
                return new UpdateResult(
                    this, packageName, source, currentVersion, requiredVersion, false, latestVersion);
            }
          } catch (IOException e) {
            throw new CompletionException(e);
          }
        });
  }

  /**
   * Legacy synchronous overload preserved for compatibility.
   *
   * <p>Deprecated: prefer {@link #requireVersionAsync(String, UpdateParameters)} which returns an
   * {@link java.util.concurrent.CompletableFuture} with an {@link UpdateResult} that callers can use
   * to control installer launch and shutdown behavior.
   *
   * <p>This legacy method delegates to {@link #requireVersionAsync(String, UpdateParameters)},
   * blocks for the result, and — if an update is required — prompts the user using the existing
   * Swing prompt flow. If the user chooses:
   * <ul>
   *   <li>IGNORE: the ignored version is persisted</li>
   *   <li>LATER: a defer-until timestamp DEFAULT_DEFER_DAYS into the future is persisted</li>
   *   <li>UPDATE_NOW: the installer is launched and an attempt to call System.exit(0) is made to
   *       preserve legacy behavior</li>
   * </ul>
   *
   * @param requiredVersion the required version string
   * @param params parameters describing the application (packageName is required)
   * @throws IOException if the async check or installer download fails
   * @deprecated Use {@link #requireVersionAsync(String, UpdateParameters)} and {@link UpdateResult#launchInstaller()}
   *     to decouple update checks from installer launch and JVM shutdown.
   */
  @Deprecated
  public void requireVersion(String requiredVersion, UpdateParameters params) throws IOException {
    try {
      // Block on the async check to preserve legacy synchronous behavior.
      UpdateResult res = requireVersionAsync(requiredVersion, params).get();

      // If no update is required (includes preference gating), return early.
      if (res == null || !res.isRequired()) {
        return;
      }

      // For legacy synchronous behavior: if an update is required, launch the installer and
      // attempt to exit the JVM. Preference persistence and prompting are handled by the async
      // flow / callers; avoid duplicating prompts here.
      res.launchInstaller();
      try {
        System.exit(0);
      } catch (SecurityException se) {
        System.err.println("requireVersion: unable to exit JVM after launching installer: " + se.getMessage());
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for update check", ie);
    } catch (java.util.concurrent.ExecutionException ee) {
      Throwable cause = ee.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException("Failed to perform update check", ee);
    }
  }

  /**
   * Holds the outcome of an update check. If {@code required} is true then the caller may invoke
   * {@link #launchInstaller()} to download and execute the installer. {@link #launchInstaller()}
   * will perform the download and attempt to start the installer but will NOT call System.exit().
   */
  public static class UpdateResult {
    private final UpdateClient owner;
    private final String packageName;
    private final String source;
    private final String currentVersion;
    private final String requiredVersion;
    private final String latestVersion;
    private final boolean required;

    UpdateResult(UpdateClient owner, String packageName, String source, String currentVersion,
        String requiredVersion, boolean required) {
      this(owner, packageName, source, currentVersion, requiredVersion, required, null);
    }

    UpdateResult(UpdateClient owner, String packageName, String source, String currentVersion,
        String requiredVersion, boolean required, String latestVersion) {
      this.owner = owner;
      this.packageName = packageName;
      this.source = source;
      this.currentVersion = currentVersion;
      this.requiredVersion = requiredVersion;
      this.required = required;
      this.latestVersion = latestVersion;
    }

    public boolean isRequired() {
      return required;
    }

    /**
     * Downloads and runs the installer for the update. Throws IOException on failures to download
     * the installer. This method does not call System.exit(); callers who need legacy behavior
     * should call System.exit(0) after invoking this method.
     */
    public void launchInstaller() throws IOException {
      if (!required) {
        return;
      }
      if (owner == null) {
        throw new IOException("Owner UpdateClient not available to perform install");
      }
      if (packageName == null || packageName.isEmpty()) {
        throw new IOException("Package name not available for installer download");
      }
      if (latestVersion == null || latestVersion.isEmpty()) {
        throw new IOException("Latest version not available for installer download");
      }

      String installer = owner.downloadInstaller(packageName, latestVersion, source, System.getProperty("java.io.tmpdir"));
      owner.runInstaller(installer);
    }

    public String getPackageName() {
      return packageName;
    }

    public String getSource() {
      return source;
    }

    public String getCurrentVersion() {
      return currentVersion;
    }

    public String getRequiredVersion() {
      return requiredVersion;
    }

    public String getLatestVersion() {
      return latestVersion;
    }
  }

  /**
   * Prompts the user to update the application using Swing dialog. Returns a tri-state decision:
   * UPDATE_NOW, LATER, IGNORE.
   *
   * @return UpdateDecision chosen by user
   */
  private UpdateDecision promptForUpdate(
      String packageName, String appTitle, String currentVersion, String requiredVersion) {
    final UpdateDecision[] result = new UpdateDecision[] {UpdateDecision.LATER};

    // If running in a headless environment, we cannot show UI — default to LATER.
    try {
      if (GraphicsEnvironment.isHeadless()) {
        return UpdateDecision.LATER;
      }
    } catch (Exception e) {
      // If detection fails for any reason, be conservative and defer the update.
      return UpdateDecision.LATER;
    }

    Runnable r =
        () -> {
          String title = (appTitle != null && !appTitle.isEmpty()) ? appTitle : packageName;
          String message =
              title
                  + " has an available update.\n\n"
                  + "Current version: "
                  + (currentVersion != null ? currentVersion : "unknown")
                  + "\n"
                  + "Required version: "
                  + (requiredVersion != null ? requiredVersion : "unknown")
                  + "\n\n"
                  + "Would you like to update now?";

          Object[] options = new Object[] {"Update Now", "Later", "Ignore This Version"};
          int opt =
              JOptionPane.showOptionDialog(
                  null,
                  message,
                  "Update Available - " + title,
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.INFORMATION_MESSAGE,
                  null,
                  options,
                  options[0]);

          switch (opt) {
            case 0:
              result[0] = UpdateDecision.UPDATE_NOW;
              break;
            case 1:
              result[0] = UpdateDecision.LATER;
              break;
            case 2:
              result[0] = UpdateDecision.IGNORE;
              break;
            default:
              // Treat closed dialog or unexpected return as LATER
              result[0] = UpdateDecision.LATER;
          }
        };

    if (SwingUtilities.isEventDispatchThread()) {
      r.run();
    } else {
      try {
        SwingUtilities.invokeAndWait(r);
      } catch (Exception e) {
        // If UI fails, default to LATER to avoid forcing update
        return UpdateDecision.LATER;
      }
    }

    return result[0];
  }

  /**
   * Finds latest available version of the package
   *
   * @param packageName
   * @param source
   * @param isPrerelease If true then it will look for pre-release versions too.
   * @return
   * @throws IOException
   */
  private String findLatestVersion(String packageName, String source, boolean isPrerelease)
      throws IOException {
    // Download and parse package-info in memory
    JsonObject packageInfo = downloadPackageInfo(packageName, source);

    // Try dist-tags.latest first if not looking for prerelease
    if (!isPrerelease) {
      JsonValue distTags = packageInfo.get("dist-tags");
      if (distTags != null && distTags.isObject()) {
        JsonValue latest = distTags.asObject().get("latest");
        if (latest != null && latest.isString()) {
          String latestVersion = latest.asString();
          // Verify this version is not a prerelease
          if (!isPrerelease(latestVersion)) {
            return latestVersion;
          }
        }
      }
    }

    // Find the greatest version from all available versions
    JsonValue versions = packageInfo.get("versions");
    if (versions == null || !versions.isObject()) {
      throw new IOException("Invalid package-info: missing versions object");
    }

    String maxVersion = null;
    for (String version : versions.asObject().names()) {
      // Skip branch versions (start with "0.0.0-")
      if (isBranchVersion(version)) {
        continue;
      }

      // Skip prereleases if not requested
      if (!isPrerelease && isPrerelease(version)) {
        continue;
      }

      // Update max version if this is greater
      if (maxVersion == null || compareVersion(maxVersion, version) < 0) {
        maxVersion = version;
      }
    }

    if (maxVersion == null) {
      throw new IOException("No suitable version found for package: " + packageName);
    }

    return maxVersion;
  }

  /**
   * Downloads and parses package-info.json for the given package and source. For GitHub packages,
   * tries package-info.json first, then package-info-2.json as fallback. For npm packages,
   * downloads from npm registry.
   *
   * @param packageName The package name
   * @param source The source URL (empty for npm, GitHub URL for GitHub packages)
   * @return Parsed JSON object of package-info
   * @throws IOException if download fails
   */
  private JsonObject downloadPackageInfo(String packageName, String source) throws IOException {
    if (isGithubSource(source)) {
      // Try downloading from GitHub with fallback
      return downloadPackageInfoFromGithub(packageName, source);
    } else {
      // Download from npm registry
      return downloadPackageInfoFromNpm(packageName);
    }
  }

  /** Checks if the source is a GitHub source. */
  private boolean isGithubSource(String source) {
    return source != null && !source.isEmpty() && source.startsWith("https://github.com/");
  }

  /** Downloads package-info from GitHub, with fallback to package-info-2.json. */
  private JsonObject downloadPackageInfoFromGithub(String packageName, String source)
      throws IOException {
    // Try package-info.json first
    try {
      String url = constructGithubPackageInfoUrl(source, "package-info.json");
      return downloadAndParsePackageInfo(url);
    } catch (IOException e) {
      // Try fallback to package-info-2.json
      try {
        String url = constructGithubPackageInfoUrl(source, "package-info-2.json");
        return downloadAndParsePackageInfo(url);
      } catch (IOException e2) {
        throw new IOException(
            "Failed to download package-info from GitHub for "
                + packageName
                + ": "
                + e2.getMessage());
      }
    }
  }

  /**
   * Constructs the GitHub URL for downloading package-info files. Uses the "jdeploy" tag to
   * download versionless package-info.
   */
  private String constructGithubPackageInfoUrl(String source, String fileName) {
    return source + "/releases/download/jdeploy/" + fileName;
  }

  /** Downloads package-info from npm registry. */
  private JsonObject downloadPackageInfoFromNpm(String packageName) throws IOException {
    String npmRegistry = System.getenv("NPM_REGISTRY_URL");
    if (npmRegistry == null || npmRegistry.isEmpty()) {
      npmRegistry = "https://registry.npmjs.org/";
    }
    if (!npmRegistry.endsWith("/")) {
      npmRegistry += "/";
    }

    String url = npmRegistry + packageName;
    return downloadAndParsePackageInfo(url);
  }

  /**
   * Downloads a file from URL and parses it as JSON. Validates that it contains a valid
   * package-info structure.
   */
  private JsonObject downloadAndParsePackageInfo(String urlString) throws IOException {
    URL url = new URL(urlString);
    String content;

    // Open a URLConnection and handle HTTP(S) specially (to check response codes).
    java.net.URLConnection urlConnection = url.openConnection();

    if (urlConnection instanceof HttpURLConnection) {
      HttpURLConnection httpConn = (HttpURLConnection) urlConnection;
      httpConn.setConnectTimeout(5000);
      httpConn.setReadTimeout(10000);
      try {
        httpConn.setRequestMethod("GET");
      } catch (ProtocolException ignored) {
        // Some HttpURLConnection implementations may not support changing the method;
        // ignore and proceed to read the input stream.
      }

      int responseCode = httpConn.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        httpConn.disconnect();
        throw new IOException(
            "HTTP error " + responseCode + " downloading package-info from " + urlString);
      }

      try (InputStream in = httpConn.getInputStream()) {
        content = readStreamToString(in);
      } finally {
        httpConn.disconnect();
      }
    } else {
      // Non-HTTP protocols (e.g., file:) - just open the stream and read the content.
      try (InputStream in = urlConnection.getInputStream()) {
        content = readStreamToString(in);
      } catch (IOException e) {
        throw new IOException("Error reading package-info from " + urlString + ": " + e.getMessage(), e);
      }
    }

    // Parse and validate JSON
    JsonObject json;
    try {
      json = Json.parse(content).asObject();
    } catch (Exception e) {
      throw new IOException("Failed to parse package-info JSON: " + e.getMessage());
    }

    // Validate that it has a versions object
    JsonValue versions = json.get("versions");
    if (versions == null || !versions.isObject()) {
      throw new IOException("Invalid package-info: missing versions object");
    }

    return json;
  }

  /** Reads an InputStream to a String. */
  private String readStreamToString(InputStream in) throws IOException {
    StringBuilder result = new StringBuilder();
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = in.read(buffer)) != -1) {
      result.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
    }
    return result.toString();
  }

  /** Checks if a version is a prerelease (contains a hyphen after the numeric part). */
  private boolean isPrerelease(String version) {
    if (version == null || version.isEmpty()) {
      return false;
    }

    // Branch versions (0.0.0-xxx) are handled separately
    if (isBranchVersion(version)) {
      return false;
    }

    // A prerelease version contains a hyphen (e.g., "1.0.0-alpha", "2.1.0-beta.1")
    String[] parts = version.split("-", 2);
    return parts.length > 1;
  }

  /**
   * Attempts to run the installer at the provided path. Platform-specific behavior:
   *
   * <ul>
   *   <li><b>macOS:</b> jDeploy delivers macOS installers as compressed archives (.tar.gz or .tgz)
   *       that contain a packaged Application bundle (a directory ending with <code>.app</code>).
   *       We extract the archive to a temporary directory, search for the first <code>.app</code>
   *       bundle, and launch it using the <code>open</code> command. While macOS historically
   *       supports <code>.dmg</code> and <code>.pkg</code> installer formats, the jDeploy
   *       distribution pipeline intentionally prefers archive-based delivery (tarballs containing
   *       .app) for simplicity and cross-platform consistency — therefore this code handles
   *       archives and .app bundles rather than expecting DMG/PKG installers from the server.
   *   <li><b>Windows:</b> installers are typically <code>.exe</code> — we launch them detached via
   *       <code>cmd /c start \"\" &lt;installer&gt;</code> so the installer runs independently of
   *       the current JVM process.
   *   <li><b>Linux / other Unix-like:</b> installers are delivered as gzip-compressed binaries
   *       (files ending in <code>.gz</code>). We decompress the gzip to a sibling file (removing
   *       the <code>.gz</code> suffix when possible), mark it executable, and execute it. For other
   *       file types we attempt to open them with <code>xdg-open</code> or execute directly if
   *       already executable.
   * </ul>
   *
   * This method is best-effort: it logs actionable errors to stderr rather than throwing unchecked
   * exceptions from this method. Before launching it verifies the installer file exists. After a
   * successful launch it does not attempt to call System.exit(0) — callers may choose to exit if
   * desired.
   *
   * @param installerPath Full path to the downloaded installer file
   */
  private void runInstaller(String installerPath) {
    if (installerPath == null || installerPath.isEmpty()) {
      System.err.println("runInstaller: installerPath is null or empty");
      return;
    }

    File installer = new File(installerPath);
    if (!installer.exists()) {
      System.err.println("runInstaller: installer not found: " + installerPath);
      return;
    }

    // Best-effort: try to mark executable on Unix-like systems
    try {
      if (!installer.canExecute()) {
        boolean set = installer.setExecutable(true);
        if (!set) {
          System.err.println(
              "runInstaller: failed to set executable bit on installer (will attempt to continue): "
                  + installerPath);
        }
      }
    } catch (Exception ignored) {
      System.err.println(
          "runInstaller: exception while attempting to set executable bit: "
              + ignored.getMessage());
    }

    String os = System.getProperty("os.name").toLowerCase();
    String lower = installerPath.toLowerCase();

    try {
      if (os.contains("mac") || os.contains("darwin")) {
        // macOS routing by extension
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
          // Verify 'tar' is available before attempting extraction
          try {
            Process tarCheck = new ProcessBuilder("tar", "--version").start();
            boolean finished = tarCheck.waitFor(5, TimeUnit.SECONDS);
            if (!finished || tarCheck.exitValue() != 0) {
              System.err.println(
                  "runInstaller: 'tar' command not available or returned non-zero. Install macOS command-line tools or GNU tar.");
              return;
            }
          } catch (Exception e) {
            System.err.println(
                "runInstaller: failed to verify availability of 'tar': " + e.getMessage());
            System.err.println(
                "runInstaller: please ensure 'tar' is installed on the system (usually provided by macOS).");
            return;
          }

          // Extract and find .app bundle
          Path extractionDir;
          try {
            extractionDir = extractTarGzToTemp(installerPath);
          } catch (IOException e) {
            System.err.println("runInstaller: failed to extract archive: " + e.getMessage());
            e.printStackTrace();
            return;
          }

          Path appBundle = findFirstAppBundle(extractionDir);
          if (appBundle == null) {
            System.err.println(
                "runInstaller: no .app bundle found inside extracted archive: " + extractionDir);
            // Log directory listing to help debugging (best-effort)
            try (Stream<Path> s = Files.walk(extractionDir)) {
              System.err.println("runInstaller: contents of extraction directory:");
              s.forEach(p -> System.err.println("  " + p.toString()));
            } catch (IOException e) {
              System.err.println(
                  "runInstaller: unable to list extraction directory contents: " + e.getMessage());
            }
            return;
          }

          try {
            ProcessBuilder pb = new ProcessBuilder("open", appBundle.toString());
            pb.start();
            System.out.println("runInstaller: Launched .app bundle: " + appBundle);
          } catch (IOException e) {
            System.err.println(
                "runInstaller: IOException while launching .app bundle: " + e.getMessage());
            e.printStackTrace();
          }
          return;
        }

        // If this is already an .app directory or a .pkg/.dmg file, open it directly
        if (lower.endsWith(".app") || lower.endsWith(".pkg") || lower.endsWith(".dmg")) {
          try {
            ProcessBuilder pb = new ProcessBuilder("open", installerPath);
            pb.start();
            System.out.println("runInstaller: Opened macOS installer: " + installerPath);
          } catch (IOException e) {
            System.err.println("runInstaller: failed to open macOS installer: " + e.getMessage());
            e.printStackTrace();
          }
          return;
        }

        // Fall back: attempt to open unknown types with 'open' (may hand off to Archive Utility)
        try {
          ProcessBuilder pb = new ProcessBuilder("open", installerPath);
          pb.start();
          System.out.println("runInstaller: Opened macOS file with 'open': " + installerPath);
        } catch (IOException e) {
          System.err.println("runInstaller: failed to open file on macOS: " + e.getMessage());
          e.printStackTrace();
        }
      } else if (os.contains("win")) {
        // Windows: preserve existing behavior for .exe or other installer types
        try {
          ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "", installerPath);
          // Do not inheritIO for cmd start; it will detach the process
          pb.start();
          System.out.println("runInstaller: Launched installer on Windows: " + installerPath);
        } catch (IOException e) {
          System.err.println(
              "runInstaller: failed to launch installer on Windows: " + e.getMessage());
          e.printStackTrace();
        }
      } else {
        // Assume Linux/other Unix-like
        if (lower.endsWith(".gz")) {
          Path gzPath = installer.toPath();
          Path decompressed;
          try {
            decompressed = decompressGzipInstaller(gzPath);
          } catch (IOException e) {
            System.err.println(
                "runInstaller: failed to decompress gzip installer: " + e.getMessage());
            e.printStackTrace();
            return;
          }

          // Make executable
          try {
            makeExecutable(decompressed);
            if (!Files.isExecutable(decompressed)) {
              System.err.println(
                  "runInstaller: decompressed file is not executable after makeExecutable: "
                      + decompressed);
              System.err.println(
                  "runInstaller: attempted chmod but it may have failed due to permissions; you may need to run as a user with sufficient rights.");
            }
          } catch (Exception e) {
            System.err.println(
                "runInstaller: exception while setting executable permissions: " + e.getMessage());
          }

          // Try to execute decompressed file
          try {
            ProcessBuilder pb = new ProcessBuilder(decompressed.toString());
            pb.inheritIO();
            pb.start();
            System.out.println("runInstaller: Launched decompressed installer: " + decompressed);
          } catch (IOException e) {
            System.err.println(
                "runInstaller: failed to execute decompressed installer: " + e.getMessage());
            e.printStackTrace();
          }
          return;
        }

        // For other file types on Linux: prefer xdg-open, else execute if executable
        try {
          ProcessBuilder pb = new ProcessBuilder("xdg-open", installerPath);
          pb.inheritIO();
          pb.start();
          System.out.println("runInstaller: Launched installer via xdg-open: " + installerPath);
        } catch (IOException xdgEx) {
          // xdg-open not available or failed; if file is executable, try to run it
          if (installer.canExecute()) {
            try {
              ProcessBuilder pb2 = new ProcessBuilder(installerPath);
              pb2.inheritIO();
              pb2.start();
              System.out.println("runInstaller: Launched executable installer: " + installerPath);
            } catch (IOException execEx) {
              System.err.println(
                  "runInstaller: failed to execute installer directly: " + execEx.getMessage());
              execEx.printStackTrace();
            }
          } else {
            System.err.println(
                "runInstaller: xdg-open failed and installer is not executable: " + installerPath);
          }
        }
      }
    } catch (Exception e) {
      System.err.println(
          "runInstaller: unexpected exception while launching installer: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Extracts a .tar.gz or .tgz archive to a temporary directory using the system 'tar' command.
   * Returns the path to the created temporary directory containing the extracted archive.
   *
   * @param archivePath Path to the .tar.gz or .tgz archive
   * @return Path to the temporary extraction directory
   * @throws IOException if extraction fails
   */
  private Path extractTarGzToTemp(String archivePath) throws IOException {
    Path tempDir = Files.createTempDirectory("jdeploy-installer-");
    ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archivePath, "-C", tempDir.toString());
    // Merge stdout and stderr so we capture any error messages
    pb.redirectErrorStream(true);

    Process p = null;
    String output = "";
    try {
      p = pb.start();
      // Capture output while waiting
      output = readStreamToString(p.getInputStream());
      boolean finished;
      try {
        finished = p.waitFor(60, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while extracting archive", ie);
      }
      if (!finished) {
        p.destroyForcibly();
        throw new IOException("tar extraction timed out");
      }
      if (p.exitValue() != 0) {
        throw new IOException("tar extraction failed (exit " + p.exitValue() + "): " + output);
      }
    } catch (IOException e) {
      // Attempt to delete tempDir on failure (best-effort)
      try {
        Files.walk(tempDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(
                pth -> {
                  try {
                    Files.deleteIfExists(pth);
                  } catch (IOException ignored) {
                  }
                });
      } catch (IOException ignored) {
      }
      throw new IOException(
          "Failed to extract archive: " + e.getMessage() + " output: " + output, e);
    }

    return tempDir;
  }

  /**
   * Recursively searches for the first directory ending with '.app' under the given root directory.
   * Returns the Path to the .app bundle directory, or null if none found.
   *
   * @param root Root directory to search
   * @return Path to first .app bundle, or null if not found
   */
  private Path findFirstAppBundle(Path root) {
    if (root == null) {
      return null;
    }
    try (Stream<Path> stream = Files.walk(root)) {
      Optional<Path> found =
          stream
              .filter(Files::isDirectory)
              .filter(
                  p ->
                      p.getFileName() != null
                          && p.getFileName().toString().toLowerCase().endsWith(".app"))
              .findFirst();
      return found.orElse(null);
    } catch (IOException e) {
      System.err.println(
          "findFirstAppBundle: IO error while searching for .app: " + e.getMessage());
      return null;
    }
  }

  /**
   * Decompresses a .gz installer file to a sibling file (removes trailing .gz suffix when
   * possible). Returns the path to the decompressed file.
   *
   * @param gzPath Path to the .gz file
   * @return Path to decompressed file
   * @throws IOException if decompression fails
   */
  private Path decompressGzipInstaller(Path gzPath) throws IOException {
    if (gzPath == null || !Files.exists(gzPath)) {
      throw new IOException("Gzip installer not found: " + gzPath);
    }

    String filename = gzPath.getFileName().toString();
    String outName;
    if (filename.toLowerCase().endsWith(".gz")) {
      outName = filename.substring(0, filename.length() - 3);
      if (outName.isEmpty()) {
        outName = filename + ".bin";
      }
    } else {
      outName = filename + ".bin";
    }

    Path dest = gzPath.getParent().resolve(outName);

    try (InputStream fis = Files.newInputStream(gzPath);
        GZIPInputStream gis = new GZIPInputStream(fis);
        FileOutputStream fos = new FileOutputStream(dest.toFile())) {

      byte[] buffer = new byte[8192];
      int read;
      while ((read = gis.read(buffer)) != -1) {
        fos.write(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new IOException("Failed to decompress gzip installer: " + e.getMessage(), e);
    }

    return dest;
  }

  /**
   * Ensures the given path is executable. Tries POSIX permission API first; if unavailable falls
   * back to `chmod`.
   *
   * @param path Path to make executable
   */
  private void makeExecutable(Path path) {
    if (path == null) {
      return;
    }
    try {
      Set<PosixFilePermission> perms = new HashSet<>();
      perms.add(PosixFilePermission.OWNER_READ);
      perms.add(PosixFilePermission.OWNER_WRITE);
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      perms.add(PosixFilePermission.GROUP_READ);
      perms.add(PosixFilePermission.GROUP_EXECUTE);
      perms.add(PosixFilePermission.OTHERS_READ);
      perms.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(path, perms);
    } catch (UnsupportedOperationException | IOException e) {
      // Fallback to chmod if POSIX permissions not supported or failed
      try {
        Process p = new ProcessBuilder("chmod", "0755", path.toString()).start();
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
          p.destroyForcibly();
          System.err.println("makeExecutable: chmod timed out for: " + path);
        }
      } catch (Exception ex) {
        System.err.println("makeExecutable: failed to make file executable: " + ex.getMessage());
      }
    }
  }

  /**
   * Downloads the installer for a specific version of an application from the jDeploy registry. The
   * installer filename is preserved from the server's Content-Disposition header,
   *
   * <p>as the installer needs this filename to determine which app it's installing.
   *
   * @param packageName The name of the package (e.g., "snapcode", "brokk")
   * @param version The version to download (e.g., "1.0.5", "0.17.1")
   * @param source The source URL for GitHub packages (e.g., "https://github.com/BrokkAi/brokk"),
   *     empty string for npm
   * @param destDir The destination directory where the installer should be saved
   * @return The full path to the downloaded installer (with proper filename)
   * @throws IOException if the download fails
   */
  private String downloadInstaller(
      String packageName, String version, String source, String destDir) throws IOException {
    String downloadURL = constructInstallerDownloadURL(packageName, version, source);
    return downloadInstallerWithFilename(downloadURL, destDir);
  }

  /**
   * Downloads a file and preserves the filename from Content-Disposition header.
   *
   * @param urlString The URL to download from
   * @param destDir The destination directory
   * @return The full path to the downloaded file
   * @throws IOException if the download fails
   */
  private String downloadInstallerWithFilename(String urlString, String destDir)
      throws IOException {
    URL url = new URL(urlString);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(2000);
    // Ensure read operations time out to avoid hanging downloads
    connection.setReadTimeout(10000);
    connection.setRequestMethod("GET");

    int responseCode = connection.getResponseCode();
    if (responseCode != HttpURLConnection.HTTP_OK) {
      throw new IOException("Bad status: " + responseCode + " " + connection.getResponseMessage());
    }

    String contentDisposition = connection.getHeaderField("Content-Disposition");
    String filename = extractFilenameFromHeader(contentDisposition);
    if (filename == null || filename.isEmpty()) {
      filename = "installer";
    }

    Path destDirPath = Paths.get(destDir);
    Files.createDirectories(destDirPath);

    Path destPath = destDirPath.resolve(filename);
    File destFile = destPath.toFile();

    try (InputStream in = connection.getInputStream();
        FileOutputStream out = new FileOutputStream(destFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
      }
    } finally {
      connection.disconnect();
    }

    return destPath.toString();
  }

  /**
   * Extracts the filename from a Content-Disposition header. Example: "attachment;
   * filename=MyApp-1.0.0_ABC.tar.gz" -> "MyApp-1.0.0_ABC.tar.gz"
   *
   * @param header The Content-Disposition header value
   * @return The extracted filename, or empty string if not found
   */
  private String extractFilenameFromHeader(String header) {
    if (header == null || header.isEmpty()) {
      return "";
    }

    Pattern pattern = Pattern.compile("filename\\s*=\\s*\"?([^\"]+)\"?");
    Matcher matcher = pattern.matcher(header);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }

    pattern = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^\"]+)");
    matcher = pattern.matcher(header);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }

    return "";
  }

  /**
   * Constructs the jDeploy registry download URL for the current platform and architecture.
   *
   * @param packageName The package name
   * @param version The version
   * @param source The source URL for GitHub packages
   * @return The constructed download URL
   * @throws IOException if platform detection fails
   */
  private String constructInstallerDownloadURL(String packageName, String version, String source)
      throws IOException {
    String baseURL = getJDeployBaseUrl();
    PlatformInfo platformInfo = detectPlatformAndFormat();

    StringBuilder url = new StringBuilder(baseURL);
    url.append("download.php?");
    url.append("platform=").append(encode(platformInfo.platform));
    url.append("&package=").append(encode(packageName));
    url.append("&version=").append(encode(version));
    url.append("&prerelease=false");
    url.append("&updates=latest");
    url.append("&source=").append(encode(source));
    if (platformInfo.format != null && !platformInfo.format.isEmpty()) {
      url.append("&format=").append(encode(platformInfo.format));
    }

    return url.toString();
  }

  /**
   * Detects the platform identifier and file format for the current runtime platform and
   * architecture.
   *
   * <p>This maps local OS/architecture to the platform and format parameters expected by the
   * jDeploy download service:
   *
   * <ul>
   *   <li><b>macOS (x86_64 / amd64):</b> platform = <code>"mac"</code>, format = <code>""</code>
   *       (empty). We intentionally request no explicit mac format so the server will deliver an
   *       archive (tarball) that contains the application bundle (.app). jDeploy currently
   *       distributes macOS payloads as <code>.tar.gz</code>/<code>.tgz</code> archives containing
   *       a <code>.app</code> bundle rather than DMG/PKG installer formats; this simplifies the
   *       distribution pipeline and allows us to extract and open the contained application bundle
   *       locally.
   *   <li><b>macOS (arm64 / aarch64):</b> platform = <code>"mac-arm64"</code>, format = <code>""
   *       </code> (empty).
   *   <li><b>Windows:</b> platform = <code>"win"</code> or <code>"win-arm64"</code>, format =
   *       <code>"exe"</code>. The server should return an <code>.exe</code> installer.
   *   <li><b>Linux:</b> platform = <code>"linux"</code> or <code>"linux-arm64"</code>, format =
   *       <code>"gz"</code>. The server will return a gzip-compressed binary which we decompress
   *       and execute locally.
   * </ul>
   *
   * <p>Throws IOException if the OS or architecture is unsupported.
   *
   * @return Platform information
   * @throws IOException if the platform is unsupported
   */
  private PlatformInfo detectPlatformAndFormat() throws IOException {
    String os = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();

    PlatformInfo info = new PlatformInfo();

    if (os.contains("mac") || os.contains("darwin")) {
      if (arch.contains("aarch64") || arch.contains("arm64")) {
        info.platform = "mac-arm64";
      } else if (arch.contains("x86_64") || arch.contains("amd64")) {
        info.platform = "mac";
      } else {
        throw new IOException("Unsupported macOS architecture: " + arch);
      }
      // Intentionally leave format empty for macOS so the server returns an archive (.tar.gz/.tgz)
      // that contains the .app bundle. We do not request DMG/PKG here because the jDeploy
      // distribution pipeline currently prefers archive-based delivery.
      info.format = "";
    } else if (os.contains("win")) {
      if (arch.contains("aarch64") || arch.contains("arm64")) {
        info.platform = "win-arm64";
      } else if (arch.contains("x86_64") || arch.contains("amd64")) {
        info.platform = "win";
      } else {
        throw new IOException("Unsupported Windows architecture: " + arch);
      }
      info.format = "exe";
    } else if (os.contains("linux")) {
      if (arch.contains("aarch64") || arch.contains("arm64")) {
        info.platform = "linux-arm64";
      } else if (arch.contains("x86_64") || arch.contains("amd64")) {
        info.platform = "linux";
      } else {
        throw new IOException("Unsupported Linux architecture: " + arch);
      }
      info.format = "gz";
    } else {
      throw new IOException("Unsupported operating system: " + os);
    }

    return info;
  }

  /**
   * Gets the jDeploy base URL from environment or uses default.
   *
   * @return The base URL
   */
  private String getJDeployBaseUrl() {
    String baseUrl = System.getenv("JDEPLOY_BASE_URL");
    if (baseUrl == null || baseUrl.isEmpty()) {
      baseUrl = "https://www.jdeploy.com/";
    }
    if (!baseUrl.endsWith("/")) {
      baseUrl += "/";
    }
    return baseUrl;
  }

  /**
   * URL encodes a string.
   *
   * @param value The value to encode
   * @return The encoded value
   */
  private String encode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
    } catch (Exception e) {
      return value;
    }
  }

  /**
   * Preference helpers and keys
   *
   * <p>Preference key names: - "update.ignore.<safeKey>" stores the ignored version string for
   * package+source - "update.deferUntil.<safeKey>" stores the defer-until timestamp (ms since
   * epoch)
   *
   * <p>The safeKey is derived from packageName + '|' + source and URL-encoded via `encode()`.
   */

  // Optional override for tests so they can use an isolated prefs node.
  private String preferencesNodeName = null;

  /**
   * Package-visible setter to direct the UpdateClient to use a specific Preferences node. Tests
   * should call this with a unique node path (for example "/test/jdeploy/..." + UUID) to avoid
   * polluting global/user preferences.
   */
  void setPreferencesNodeName(String nodeName) {
    this.preferencesNodeName = nodeName;
  }

  private Preferences preferences() {
    if (preferencesNodeName != null && !preferencesNodeName.isEmpty()) {
      try {
        return Preferences.userRoot().node(preferencesNodeName);
      } catch (Exception ignored) {
        // Fallthrough to default if any error occurs
      }
    }
    return Preferences.userNodeForPackage(UpdateClient.class);
  }

  private String safeKeyFor(String packageName, String source) {
    return encode((packageName == null ? "" : packageName) + "|" + (source == null ? "" : source));
  }

  // The following preference helpers are package-private so unit tests in the same package
  // can directly manipulate and assert preference state without relying on UI/IO flows.

  String getIgnoredVersion(String packageName, String source) {
    return preferences().get("update.ignore." + safeKeyFor(packageName, source), null);
  }

  void setIgnoredVersion(String packageName, String source, String version) {
    String key = "update.ignore." + safeKeyFor(packageName, source);
    if (version == null) {
      preferences().remove(key);
    } else {
      preferences().put(key, version);
    }
  }

  long getDeferUntil(String packageName, String source) {
    return preferences().getLong("update.deferUntil." + safeKeyFor(packageName, source), 0L);
  }

  void setDeferUntil(String packageName, String source, long until) {
    preferences().putLong("update.deferUntil." + safeKeyFor(packageName, source), until);
  }

  /**
   * Helper to evaluate whether prompting should be skipped for the given package/source/version.
   * This encapsulates the early preferences gating logic (Ignore / Later) so tests can verify it
   * without invoking file-system or network operations.
   *
   * <p>Returns true if: - The user has chosen to ignore this exact requiredVersion for the
   * package/source, OR - The user deferred updates (deferUntil) and the current time is before that
   * timestamp.
   */
  boolean shouldSkipPrompt(String packageName, String source, String requiredVersion) {
    String ignoredVersion = getIgnoredVersion(packageName, source);
    if (ignoredVersion != null
        && !ignoredVersion.isEmpty()
        && ignoredVersion.equals(requiredVersion)) {
      return true;
    }
    long deferUntil = getDeferUntil(packageName, source);
    return System.currentTimeMillis() < deferUntil;
  }

  /** Holds platform and format information. */
  private static class PlatformInfo {
    String platform;
    String format;
  }

  /** Holds package information from package.json. */
  private static class PackageInfo {
    String name;
    String version;
    String source;
    String minLauncherInitialAppVersion;
    String appTitle;
  }

  /**
   * Finds the package.json file by traversing up from the jar file location. The jar file should be
   * in a jdeploy-bundle directory, and package.json should be in the parent directory.
   *
   * @param jarPath The path to the jar file (or any file in the jdeploy-bundle directory)
   * @return The path to package.json
   * @throws IOException if package.json cannot be found
   */
  private Path findPackageJson(String jarPath) throws IOException {
    Path currentPath = Paths.get(jarPath).toAbsolutePath();

    // Traverse up the directory tree looking for package.json
    while (currentPath != null) {
      Path packageJsonPath = currentPath.resolve("package.json");
      if (Files.exists(packageJsonPath)) {
        return packageJsonPath;
      }

      // If we're in jdeploy-bundle, check parent
      if (currentPath.getFileName() != null
          && currentPath.getFileName().toString().equals("jdeploy-bundle")) {
        Path parentPackageJson = currentPath.getParent().resolve("package.json");
        if (Files.exists(parentPackageJson)) {
          return parentPackageJson;
        }
      }

      currentPath = currentPath.getParent();
    }

    throw new IOException("package.json not found in any parent directory of: " + jarPath);
  }

  /**
   * Parses package.json and extracts name, version, source, and
   * jdeploy.minLauncherInitialAppVersion properties. Uses minimal-json library (shaded to avoid
   * conflicts).
   *
   * @param packageJsonPath The path to package.json
   * @return PackageInfo containing the parsed properties
   * @throws IOException if the file cannot be read or parsed
   */
  private PackageInfo parsePackageJson(Path packageJsonPath) throws IOException {
    String content = new String(Files.readAllBytes(packageJsonPath), StandardCharsets.UTF_8);

    JsonObject json = Json.parse(content).asObject();

    PackageInfo info = new PackageInfo();

    JsonValue nameValue = json.get("name");
    info.name = (nameValue != null && nameValue.isString()) ? nameValue.asString() : null;

    JsonValue versionValue = json.get("version");
    info.version =
        (versionValue != null && versionValue.isString()) ? versionValue.asString() : null;

    JsonValue sourceValue = json.get("source");
    info.source = (sourceValue != null && sourceValue.isString()) ? sourceValue.asString() : "";

    // Extract jdeploy.minLauncherInitialAppVersion if it exists
    JsonValue jdeployValue = json.get("jdeploy");
    if (jdeployValue != null && jdeployValue.isObject()) {
      JsonObject jdeployObj = jdeployValue.asObject();
      JsonValue minLauncherValue = jdeployObj.get("minLauncherInitialAppVersion");
      info.minLauncherInitialAppVersion =
          (minLauncherValue != null && minLauncherValue.isString())
              ? minLauncherValue.asString()
              : null;
      JsonValue appTitleValue = jdeployObj.get("title");
      info.appTitle =
          (appTitleValue != null && appTitleValue.isString())
              ? appTitleValue.asString()
              : info.name;
    } else {
      info.minLauncherInitialAppVersion = null;
      info.appTitle = info.name;
    }

    if (info.name == null || info.name.isEmpty()) {
      throw new IOException("package.json missing required 'name' property");
    }
    if (info.version == null || info.version.isEmpty()) {
      throw new IOException("package.json missing required 'version' property");
    }

    return info;
  }

  /**
   * Checks if a version string represents a branch version. Branch versions start with "0.0.0-".
   *
   * @param version The version string to check
   * @return true if this is a branch version, false otherwise
   */
  private boolean isBranchVersion(String version) {
    return version != null && version.startsWith("0.0.0-");
  }

  /**
   * Compares two semantic version strings.
   *
   * @param version1 The first version
   * @param version2 The second version
   * @return -1 if version1 < version2, 0 if version1 == version2, 1 if version1 > version2
   */
  private int compareVersion(String version1, String version2) {
    if (version1 == null || version2 == null) {
      return 0;
    }

    // Split the version into numeric and pre-release parts
    String[] v1Parts = version1.split("-", 2);
    String[] v2Parts = version2.split("-", 2);

    // Compare the numeric parts (e.g., "1.0.0" in "1.0.0-alpha")
    int numericComparison = compareNumericParts(v1Parts[0], v2Parts[0]);
    if (numericComparison != 0) {
      return numericComparison;
    }

    // If the numeric parts are equal, compare pre-release parts
    boolean v1HasPreRelease = v1Parts.length > 1;
    boolean v2HasPreRelease = v2Parts.length > 1;

    if (!v1HasPreRelease && v2HasPreRelease) {
      // A version without a pre-release part has higher precedence
      return 1;
    } else if (v1HasPreRelease && !v2HasPreRelease) {
      // A version with a pre-release part has lower precedence
      return -1;
    } else if (v1HasPreRelease && v2HasPreRelease) {
      // Both have pre-release parts, compare them lexicographically
      return v1Parts[1].compareTo(v2Parts[1]);
    }

    // If both versions are equal
    return 0;
  }

  /**
   * Compares the numeric parts of two versions (e.g., "1.0.0" vs "1.0.1").
   *
   * @param numeric1 The first numeric version part
   * @param numeric2 The second numeric version part
   * @return -1 if numeric1 < numeric2, 0 if equal, 1 if numeric1 > numeric2
   */
  private int compareNumericParts(String numeric1, String numeric2) {
    String[] v1Segments = numeric1.split("\\.");
    String[] v2Segments = numeric2.split("\\.");
    int maxLen = Math.max(v1Segments.length, v2Segments.length);

    for (int i = 0; i < maxLen; i++) {
      int v1 = getSegmentValue(v1Segments, i);
      int v2 = getSegmentValue(v2Segments, i);
      if (v1 > v2) {
        return 1;
      }
      if (v1 < v2) {
        return -1;
      }
    }

    return 0;
  }

  /**
   * Returns the integer value of the version segment at the given index, or 0 if out of range.
   *
   * @param segments The version segments
   * @param index The index to retrieve
   * @return The integer value, or 0 if index is out of bounds or not a number
   */
  private int getSegmentValue(String[] segments, int index) {
    if (index >= segments.length) {
      return 0;
    }
    try {
      return Integer.parseInt(segments[index]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
