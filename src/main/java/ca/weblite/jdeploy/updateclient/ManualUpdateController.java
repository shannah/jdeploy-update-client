package ca.weblite.jdeploy.updateclient;

import java.io.IOException;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * ManualUpdateController
 *
 * <p>Small manual runner useful for testing UpdateClient end-to-end. Edit the variables in main()
 * to configure the test parameters (packageName, source, projectCode, requiredVersion, and the
 * relevant system properties). This class calls UpdateClient.requireVersion(requiredVersion,
 * params) and, if the update flow does not cause the process to exit (installer launched), it
 * displays a simple "Hello World" window so you can verify the application continued to run.
 *
 * <p>Notes: - UpdateClient requires the system property {@code jdeploy.app.version} to be present
 * or it will return immediately without prompting. The launcher's comparison uses * {@code
 * jdeploy.launcher.app.version} (defaults to "0.0.0" when absent).
 *
 * <p>Run hint: Use your IDE's Run action for this class, or run with a Java exec plugin that
 * supports specifying the main class.
 */
public final class ManualUpdateController {

  private ManualUpdateController() {
    // utility class
  }

  public static void main(String[] args) throws IOException {
    // ---------------------------------------------------------------------
    // Edit these variables for different test scenarios
    // ---------------------------------------------------------------------
    String packageName = "brokk"; // package name used by UpdateParameters.Builder
    String source =
        "https://github.com/BrokkAi/brokk"; // e.g. "" for npm, or "https://github.com/user/repo"
    // for GitHub source
    String projectCode = "262N"; // used to isolate preferences via setPreferencesNodeName
    String requiredVersion = "0.17.2"; // required version to pass to requireVersion()
    String appTitle = "Brokk"; // optional app title shown in update prompt
    String jdeployAppVersion =
        "0.17.1"; // sets System property jdeploy.app.version (must be present to run checks)
    String jdeployLauncherAppVersion =
        "0.17.1"; // sets System property jdeploy.launcher.app.version (used for comparison)
    String prereleaseFlag =
        "false"; // optional: "true" or "false" for System property jdeploy.prerelease
    // ---------------------------------------------------------------------

    // Echo configuration for traceability
    System.out.println("ManualUpdateController configuration:");
    System.out.println("  packageName:                  " + packageName);
    System.out.println("  source:                       " + (source == null ? "" : source));
    System.out.println("  projectCode (prefs node):     " + projectCode);
    System.out.println("  requiredVersion:              " + requiredVersion);
    System.out.println("  appTitle:                     " + appTitle);
    System.out.println("  jdeploy.app.version:          " + jdeployAppVersion);
    System.out.println("  jdeploy.launcher.app.version: " + jdeployLauncherAppVersion);
    System.out.println("  jdeploy.prerelease:           " + prereleaseFlag);
    System.out.println();

    // Set required system properties for UpdateClient behavior
    if (jdeployAppVersion != null) {
      System.setProperty("jdeploy.app.version", jdeployAppVersion);
    }
    if (jdeployLauncherAppVersion != null) {
      System.setProperty("jdeploy.launcher.app.version", jdeployLauncherAppVersion);
    }
    if (prereleaseFlag != null) {
      System.setProperty("jdeploy.prerelease", prereleaseFlag);
    }

    // Build UpdateParameters (currentVersion is informational only for this runner)
    UpdateParameters params =
        new UpdateParameters.Builder(packageName)
            .source(source)
            .appTitle((appTitle != null && !appTitle.isEmpty()) ? appTitle : packageName)
            .currentVersion("manual-runner-current-version")
            .build();

    // Create client and isolate preferences for this run
    UpdateClient client = new UpdateClient();
    if (projectCode != null && !projectCode.isEmpty()) {
      client.setPreferencesNodeName(projectCode);
    }

    // Invoke the asynchronous update check. If an update is required, launch the installer and
    // exit the JVM to simulate legacy behavior. Otherwise, continue running and show the window.
    client.requireVersionAsync(requiredVersion, params)
        .thenAccept(
            result -> {
              if (result != null && result.isRequired()) {
                try {
                  result.launchInstaller();
                  System.exit(0);
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
            });

    // Show a simple window so we can verify the run continued past the update check.
    SwingUtilities.invokeLater(
        () -> {
          JFrame frame = new JFrame("Manual Update Runner");
          StringBuilder sb = new StringBuilder();
          sb.append("<html><div style='text-align:center;'>");
          sb.append("<h2>Hello World</h2>");
          sb.append("<div style='margin-top:8px;font-size:90%'>");
          sb.append("package: ").append(escapeHtml(packageName)).append("<br/>");
          sb.append("required: ").append(escapeHtml(requiredVersion)).append("<br/>");
          sb.append("jdeploy.app.version: ").append(escapeHtml(jdeployAppVersion)).append("<br/>");
          sb.append("jdeploy.launcher.app.version: ").append(escapeHtml(jdeployLauncherAppVersion));
          sb.append("</div></div></html>");

          JLabel label = new JLabel(sb.toString(), SwingConstants.CENTER);
          frame.getContentPane().add(label);
          frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
          frame.setSize(480, 220);
          frame.setLocationRelativeTo(null);
          frame.setVisible(true);
        });
  }

  /** Minimal HTML-escaping for values rendered inside an HTML JLabel. */
  private static String escapeHtml(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
