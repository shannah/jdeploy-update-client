package ca.weblite.jdeploy.updateclient;

/**
 * Parameters that describe an application for update checking.
 *
 * <p>This immutable value object provides the application metadata required by {@link
 * UpdateClient#requireVersion(String, UpdateParameters)} so callers do not need to rely on a local
 * {@code package.json} file or other filesystem access. This makes it suitable for bundled
 * applications, sandboxed environments, or any scenario where reading local project files is
 * undesirable or impossible.
 *
 * <p>Field meanings:
 *
 * <ul>
 *   <li><b>packageName</b> (required) - The package identifier (e.g. "snapcode" or "@scope/name").
 *       This should match the name published to the registry (npm) or the identifier used by the
 *       jDeploy download service.
 *   <li><b>source</b> (optional) - Controls which backend the updater uses:
 *       <ul>
 *         <li>GitHub-hosted packages: supply the repository URL (for example, {@code
 *             https://github.com/Owner/Repo}). The updater will attempt to fetch package-info files
 *             from GitHub releases.
 *         <li>npm-hosted packages: leave {@code source} null or empty to instruct the updater to
 *             use the npm registry workflow.
 *       </ul>
 *   <li><b>appTitle</b> (optional) - Human-friendly application title to display in UI prompts. If
 *       not provided, callers or UIs should fall back to {@code packageName}.
 *   <li><b>currentVersion</b> (optional) - The currently-installed version string (e.g. "1.2.3").
 *       If omitted the updater will fall back to the {@code jdeploy.app.version} system property to
 *       preserve legacy behavior.
 * </ul>
 *
 * <p>Because this object supplies all required metadata, the parameters-based workflow requires no
 * local project files (for example, {@code package.json}). Use the {@link Builder} for convenient
 * construction:
 *
 * <pre>
 * UpdateParameters params = new UpdateParameters.Builder("my-package")
 *     .source("https://github.com/Owner/Repo") // Omit or set to empty string for npm-hosted packages
 *     .appTitle("My App")
 *     .currentVersion("1.0.0")
 *     .build();
 * </pre>
 */
public final class UpdateParameters {

  private final String packageName;
  private final String source;
  private final String appTitle;
  private final String currentVersion;

  /**
   * Creates a new UpdateParameters instance.
   *
   * @param packageName The package name (required, non-null, non-empty)
   * @param source The source URL for GitHub-hosted packages, or null/empty for npm
   * @param appTitle Optional application title (may be null)
   * @param currentVersion Optional installed/current version (may be null)
   * @throws IllegalArgumentException if packageName is null or empty
   */
  public UpdateParameters(
      String packageName, String source, String appTitle, String currentVersion) {
    if (packageName == null || packageName.trim().isEmpty()) {
      throw new IllegalArgumentException("packageName is required and must not be empty");
    }
    this.packageName = packageName;
    this.source = (source == null || source.isEmpty()) ? "" : source;
    this.appTitle = (appTitle == null || appTitle.isEmpty()) ? null : appTitle;
    this.currentVersion =
        (currentVersion == null || currentVersion.isEmpty()) ? null : currentVersion;
  }

  /** Returns the package name (never null). */
  public String getPackageName() {
    return packageName;
  }

  /** Returns the source URL for GitHub-hosted packages, or empty string if none. */
  public String getSource() {
    return source;
  }

  /**
   * Returns the human-friendly application title, or null if not provided. Callers may fall back to
   * {@link #getPackageName()} when null.
   */
  public String getAppTitle() {
    return appTitle;
  }

  /** Returns the currently-installed version string, or null if unknown. */
  public String getCurrentVersion() {
    return currentVersion;
  }

  @Override
  public String toString() {
    return "UpdateParameters{"
        + "packageName='"
        + packageName
        + '\''
        + ", source='"
        + source
        + '\''
        + ", appTitle='"
        + appTitle
        + '\''
        + ", currentVersion='"
        + currentVersion
        + '\''
        + '}';
  }

  /** Builder for {@link UpdateParameters}. */
  public static final class Builder {
    private final String packageName;
    private String source;
    private String appTitle;
    private String currentVersion;

    /**
     * Builder constructor with required package name.
     *
     * @param packageName required non-null, non-empty package name
     */
    public Builder(String packageName) {
      if (packageName == null || packageName.trim().isEmpty()) {
        throw new IllegalArgumentException("packageName is required and must not be empty");
      }
      this.packageName = packageName;
    }

    /**
     * Set the source URL for GitHub-hosted packages (e.g. "https://github.com/Owner/Repo"). Leave
     * unset or set to null/empty for npm-hosted packages.
     */
    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /** Set an optional application title to display in UI prompts. */
    public Builder appTitle(String appTitle) {
      this.appTitle = appTitle;
      return this;
    }

    /** Set the currently-installed version string (e.g. "1.2.3"); optional. */
    public Builder currentVersion(String currentVersion) {
      this.currentVersion = currentVersion;
      return this;
    }

    /** Builds the {@link UpdateParameters} instance. */
    public UpdateParameters build() {
      return new UpdateParameters(packageName, source, appTitle, currentVersion);
    }
  }
}
