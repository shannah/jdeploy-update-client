package ca.weblite.jdeploy.updateclient;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

/**
 * Client for checking and installing application updates.
 */
public class UpdateClient {

    // Defer period in days when user chooses "Later"
    private static final int DEFAULT_DEFER_DAYS = 7;

    private enum UpdateDecision {
        UPDATE_NOW,
        LATER,
        IGNORE
    }

    public void requireVersion(String version) {
        String jdeployAppVersion = System.getProperty("jdeploy.app.version");
        if (jdeployAppVersion == null || isBranchVersion(jdeployAppVersion) || compareVersion(jdeployAppVersion, version) >= 0) {
           return;
        }

        boolean isPrerelease = "true".equals(System.getProperty("jdeploy.prerelease", "false"));

        try {
            // Parse package.json to identify package/source (avoid network if user has ignored/deferral set)
            PackageInfo packageInfo = parsePackageJson(findPackageJson(findCurrentJarPath().toString()));

            // Early preferences gating:
            // - "update.ignore.<safeKey>" stores an ignored version string for a package+source
            // - "update.deferUntil.<safeKey>" stores a timestamp (ms since epoch) until which updates are deferred
            // If the user has chosen "Ignore" for this package/source (matching the required version), return.
            String ignoredVersion = getIgnoredVersion(packageInfo.name, packageInfo.source);
            if (ignoredVersion != null && !ignoredVersion.isEmpty() && ignoredVersion.equals(version)) {
                return;
            }

            // If the user deferred updates until a future time, and that time has not yet passed, return.
            long deferUntil = getDeferUntil(packageInfo.name, packageInfo.source);
            if (System.currentTimeMillis() < deferUntil) {
                return;
            }

            // Now fetch latest version (network)
            String latestVersion = findLatestVersion(packageInfo.name, packageInfo.source, isPrerelease);

            // If user chose to ignore this specific latest version previously, skip prompting
            if (ignoredVersion != null && !ignoredVersion.isEmpty() && ignoredVersion.equals(latestVersion)) {
                return;
            }

            UpdateDecision decision = promptForUpdate(packageInfo.name, packageInfo.appTitle, jdeployAppVersion, version, packageInfo.source);

            if (decision == UpdateDecision.IGNORE) {
                // Persist ignored version so we don't prompt again for this exact version
                setIgnoredVersion(packageInfo.name, packageInfo.source, latestVersion);
                return;
            } else if (decision == UpdateDecision.LATER) {
                long until = System.currentTimeMillis() + (long) DEFAULT_DEFER_DAYS * 24L * 60L * 60L * 1000L;
                setDeferUntil(packageInfo.name, packageInfo.source, until);
                return;
            } else {
                // UPDATE_NOW - proceed to download & run installer for latestVersion
                String installer = downloadInstaller(packageInfo.name, latestVersion, packageInfo.source, System.getProperty("java.io.tmpdir"));
                runInstaller(installer);
            }
        } catch (IOException e) {
            // On any IO error, we silently ignore update attempt
            return;
        }

    }

    /**
     * Prompts the user to update the application using Swing dialog.
     * Returns a tri-state decision: UPDATE_NOW, LATER, IGNORE.
     *
     * @param packageName
     * @param appTitle
     * @param currentVersion
     * @param requiredVersion
     * @param source
     * @return UpdateDecision chosen by user
     */
    private UpdateDecision promptForUpdate(String packageName, String appTitle, String currentVersion, String requiredVersion, String source) {
        final UpdateDecision[] result = new UpdateDecision[]{UpdateDecision.LATER};

        // If running in a headless environment, we cannot show UI — default to LATER.
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return UpdateDecision.LATER;
            }
        } catch (Exception e) {
            // If detection fails for any reason, be conservative and defer the update.
            return UpdateDecision.LATER;
        }

        Runnable r = () -> {
            String title = (appTitle != null && !appTitle.isEmpty()) ? appTitle : packageName;
            String message = title + " has an available update.\n\n" +
                    "Current version: " + (currentVersion != null ? currentVersion : "unknown") + "\n" +
                    "Required version: " + (requiredVersion != null ? requiredVersion : "unknown") + "\n\n" +
                    "Would you like to update now?";

            Object[] options = new Object[] {"Update Now", "Later", "Ignore This Version"};
            int opt = JOptionPane.showOptionDialog(
                    null,
                    message,
                    "Update Available - " + title,
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null,
                    options,
                    options[0]
            );

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
     * @param packageName
     * @param source
     * @param isPrerelease If true then it will look for pre-release versions too.
     * @return
     * @throws IOException
     */
    private String findLatestVersion(String packageName, String source, boolean isPrerelease) throws IOException {
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
     * Downloads and parses package-info.json for the given package and source.
     * For GitHub packages, tries package-info.json first, then package-info-2.json as fallback.
     * For npm packages, downloads from npm registry.
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

    /**
     * Checks if the source is a GitHub source.
     */
    private boolean isGithubSource(String source) {
        return source != null && !source.isEmpty() && source.startsWith("https://github.com/");
    }

    /**
     * Downloads package-info from GitHub, with fallback to package-info-2.json.
     */
    private JsonObject downloadPackageInfoFromGithub(String packageName, String source) throws IOException {
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
                throw new IOException("Failed to download package-info from GitHub for " + packageName + ": " + e2.getMessage());
            }
        }
    }

    /**
     * Constructs the GitHub URL for downloading package-info files.
     * Uses the "jdeploy" tag to download versionless package-info.
     */
    private String constructGithubPackageInfoUrl(String source, String fileName) {
        return source + "/releases/download/jdeploy/" + fileName;
    }

    /**
     * Downloads package-info from npm registry.
     */
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
     * Downloads a file from URL and parses it as JSON.
     * Validates that it contains a valid package-info structure.
     */
    private JsonObject downloadAndParsePackageInfo(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP error " + responseCode + " downloading package-info from " + urlString);
        }

        String content;
        try (InputStream in = connection.getInputStream()) {
            content = readStreamToString(in);
        } finally {
            connection.disconnect();
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

    /**
     * Reads an InputStream to a String.
     */
    private String readStreamToString(InputStream in) throws IOException {
        StringBuilder result = new StringBuilder();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            result.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    /**
     * Checks if a version is a prerelease (contains a hyphen after the numeric part).
     */
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
     * Persist or remove the "ignore this version" flag for a package+source.
     *
     * @param packageName package name
     * @param source package source
     * @param version if null -> remove the ignore preference, otherwise persist the ignored version string
     */
    private void markUpdateDeclined(String packageName, String source, String version) {
        setIgnoredVersion(packageName, source, version);
    }

    private void runInstaller(String installerPath) {
        if (installerPath == null || installerPath.isEmpty()) {
            return;
        }

        try {
            File installer = new File(installerPath);
            if (!installer.exists()) {
                return;
            }

            // Attempt to make executable on Unix-like systems
            try {
                if (!installer.canExecute()) {
                    installer.setExecutable(true);
                }
            } catch (Exception ignored) {
            }

            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") && installerPath.endsWith(".dmg")) {
                // Use 'open' for dmg on macOS
                pb = new ProcessBuilder("open", installerPath);
            } else if (os.contains("mac") && installerPath.endsWith(".pkg")) {
                pb = new ProcessBuilder("open", installerPath);
            } else if (os.contains("win")) {
                pb = new ProcessBuilder(installerPath);
            } else {
                // Linux/other - attempt to execute directly
                pb = new ProcessBuilder(installerPath);
            }

            pb.inheritIO();
            pb.start();
        } catch (IOException e) {
            // Best effort; if launching fails, just return
            e.printStackTrace();
        } finally {
            // Exit the JVM to allow installer to proceed / replace files if needed
            try {
                System.exit(0);
            } catch (SecurityException ignored) {
            }
        }
    }


    private Path findCurrentJarPath() throws IOException {
        String path = UpdateClient.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return Paths.get(path).toAbsolutePath();
    }


    /**
     * Checks if an update is available for the application.
     *
     * @return true if an update is available, false otherwise
     */
    private boolean checkForUpdates() {
        // TODO: Implement update checking logic
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Determines if a launcher update is required based on the minimum launcher initial app version.
     *
     * Returns false if:
     * - update-manifest.json exists (launcher handles updates natively), OR
     * - minLauncherInitialAppVersion doesn't exist in package.json, OR
     * - version is a branch version (starts with "0.0.0-"), OR
     * - version >= minLauncherInitialAppVersion
     *
     * Returns true if:
     * - update-manifest.json does NOT exist AND
     * - minLauncherInitialAppVersion exists AND
     * - version is not a branch version AND
     * - version < minLauncherInitialAppVersion
     *
     * @param jarPath Path to the jar file (used to find package.json)
     * @param version The current version to check
     * @return true if launcher update is required, false otherwise
     * @throws IOException if package.json cannot be found or parsed
     */
    private boolean requiresLauncherUpdate(String jarPath, String version) throws IOException {
        // Find package.json
        Path packageJsonPath = findPackageJson(jarPath);

        // Check for update-manifest.json in the parent directory
        // If it exists, the launcher is new enough to handle updates natively
        Path parentDir = packageJsonPath.getParent().getParent();
        if (parentDir != null) {
            Path updateManifestPath = parentDir.resolve("update-manifest.json");
            if (Files.exists(updateManifestPath)) {
                // Launcher handles updates natively
                return false;
            }
        }

        // Parse package.json
        PackageInfo packageInfo = parsePackageJson(packageJsonPath);

        // Return false if minLauncherInitialAppVersion doesn't exist
        if (packageInfo.minLauncherInitialAppVersion == null ||
            packageInfo.minLauncherInitialAppVersion.isEmpty()) {
            return false;
        }

        // Return false if version is a branch version (starts with "0.0.0-")
        if (isBranchVersion(version)) {
            return false;
        }

        // Return true if version < minLauncherInitialAppVersion
        return compareVersion(version, packageInfo.minLauncherInitialAppVersion) < 0;
    }

    /**
     * Downloads and runs the installer for the latest version.
     *
     * @throws Exception if the download or installation fails
     */
    private void downloadAndRunInstaller() throws Exception {
        // TODO: Implement download and installer execution logic
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Downloads the installer for a specific version of an application from the jDeploy registry.
     * The installer filename is preserved from the server's Content-Disposition header,
     *
     *
     * as the installer needs this filename to determine which app it's installing.
     *
     * @param packageName The name of the package (e.g., "snapcode", "brokk")
     * @param version The version to download (e.g., "1.0.5", "0.17.1")
     * @param source The source URL for GitHub packages (e.g., "https://github.com/BrokkAi/brokk"), empty string for npm
     * @param destDir The destination directory where the installer should be saved
     * @return The full path to the downloaded installer (with proper filename)
     * @throws IOException if the download fails
     */
    private String downloadInstaller(String packageName, String version, String source, String destDir) throws IOException {
        String downloadURL = constructInstallerDownloadURL(packageName, version, source);
        String installerPath = downloadInstallerWithFilename(downloadURL, destDir);
        return installerPath;
    }

    /**
     * Downloads a file and preserves the filename from Content-Disposition header.
     *
     * @param urlString The URL to download from
     * @param destDir The destination directory
     * @return The full path to the downloaded file
     * @throws IOException if the download fails
     */
    private String downloadInstallerWithFilename(String urlString, String destDir) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2000);
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
     * Extracts the filename from a Content-Disposition header.
     * Example: "attachment; filename=MyApp-1.0.0_ABC.tar.gz" -> "MyApp-1.0.0_ABC.tar.gz"
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
    private String constructInstallerDownloadURL(String packageName, String version, String source) throws IOException {
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
     * Detects the platform identifier and file format for the current runtime platform and architecture.
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
     * Preference key names:
     * - "update.ignore.<safeKey>" stores the ignored version string for package+source
     * - "update.deferUntil.<safeKey>" stores the defer-until timestamp (ms since epoch)
     *
     * The safeKey is derived from packageName + '|' + source and URL-encoded via `encode()`.
     */

    private Preferences preferences() {
        return Preferences.userNodeForPackage(UpdateClient.class);
    }

    private String safeKeyFor(String packageName, String source) {
        return encode((packageName == null ? "" : packageName) + "|" + (source == null ? "" : source));
    }

    private String getIgnoredVersion(String packageName, String source) {
        return preferences().get("update.ignore." + safeKeyFor(packageName, source), null);
    }

    private void setIgnoredVersion(String packageName, String source, String version) {
        String key = "update.ignore." + safeKeyFor(packageName, source);
        if (version == null) {
            preferences().remove(key);
        } else {
            preferences().put(key, version);
        }
    }

    private long getDeferUntil(String packageName, String source) {
        return preferences().getLong("update.deferUntil." + safeKeyFor(packageName, source), 0L);
    }

    private void setDeferUntil(String packageName, String source, long until) {
        preferences().putLong("update.deferUntil." + safeKeyFor(packageName, source), until);
    }

    /**
     * Holds platform and format information.
     */
    private static class PlatformInfo {
        String platform;
        String format;
    }

    /**
     * Holds package information from package.json.
     */
    private static class PackageInfo {
        String name;
        String version;
        String source;
        String minLauncherInitialAppVersion;
        String appTitle;
    }

    /**
     * Finds the package.json file by traversing up from the jar file location.
     * The jar file should be in a jdeploy-bundle directory, and package.json
     * should be in the parent directory.
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
            if (currentPath.getFileName() != null &&
                currentPath.getFileName().toString().equals("jdeploy-bundle")) {
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
     * Parses package.json and extracts name, version, source, and jdeploy.minLauncherInitialAppVersion properties.
     * Uses minimal-json library (shaded to avoid conflicts).
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
        info.version = (versionValue != null && versionValue.isString()) ? versionValue.asString() : null;

        JsonValue sourceValue = json.get("source");
        info.source = (sourceValue != null && sourceValue.isString()) ? sourceValue.asString() : "";

        // Extract jdeploy.minLauncherInitialAppVersion if it exists
        JsonValue jdeployValue = json.get("jdeploy");
        if (jdeployValue != null && jdeployValue.isObject()) {
            JsonObject jdeployObj = jdeployValue.asObject();
            JsonValue minLauncherValue = jdeployObj.get("minLauncherInitialAppVersion");
            info.minLauncherInitialAppVersion = (minLauncherValue != null && minLauncherValue.isString())
                ? minLauncherValue.asString() : null;
            JsonValue appTitleValue = jdeployObj.get("title");
            info.appTitle = (appTitleValue != null && appTitleValue.isString())
                    ?appTitleValue.asString() : info.name;
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
     * Checks if a version string represents a branch version.
     * Branch versions start with "0.0.0-".
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
