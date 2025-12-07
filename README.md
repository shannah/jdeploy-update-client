# jDeploy Update Client Swing

A Swing-based update client library for jDeploy applications that provides a smooth launcher update experience.

## When Do You Need This Library?

**Important:** This library is **not necessary** for jDeploy apps deployed with jDeploy 5.5.4 or later, as those launchers handle full launcher updates automatically.

However, if your application was deployed **before jDeploy 5.5.4** and you need to push an update that requires the latest launcher (because it uses newer jDeploy features), this library provides a smoother update path for your users by:

- Detecting when a launcher update is required
- Presenting a user-friendly Swing dialog prompting for the update
- Downloading and installing the new launcher
- Gracefully handling the transition

If you're starting a new jDeploy project or your users are already on jDeploy 5.5.4+, you don't need this library.

## Maven Coordinates

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-update-client-swing</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'ca.weblite:jdeploy-update-client-swing:1.0.0'
```

## Features

- **Async-first API** - Non-blocking update checks with CompletableFuture support
- **User-controlled updates** - Prompts users with "Update Now", "Later", or "Ignore" options
- **Headless support** - Gracefully degrades in headless environments
- **GitHub and npm support** - Works with packages hosted on GitHub releases or npm registry
- **No filesystem dependencies** - Parameters-based API requires no local package.json files
- **Caller-controlled lifecycle** - Never calls System.exit(), allowing proper cleanup before shutdown

## Basic Usage

### Quick Start (Async API - Recommended)

```java
import ca.weblite.jdeploy.updateclient.UpdateClient;
import ca.weblite.jdeploy.updateclient.UpdateParameters;

public class MyApp {
    public static void main(String[] args) {
        // Create update client
        UpdateClient client = new UpdateClient();

        // Define your app parameters
        UpdateParameters params = new UpdateParameters.Builder("my-package-name")
            .appTitle("My Application")
            .currentVersion("1.0.0")
            .source("https://github.com/username/repo") // For GitHub-hosted apps
            // .source("") // Leave empty or omit for npm-hosted apps
            .build();

        // Check if launcher update is required
        client.requireVersionAsync("2.0.0", params)
            .thenAccept(result -> {
                if (result.isRequired()) {
                    try {
                        // Launch the installer
                        result.launchInstaller();

                        // Perform any cleanup (save state, flush logs, etc.)
                        cleanup();

                        // Exit the application
                        System.exit(0);
                    } catch (IOException e) {
                        System.err.println("Failed to launch installer: " + e.getMessage());
                    }
                } else {
                    // Continue with normal application startup
                    launchApp();
                }
            })
            .exceptionally(ex -> {
                System.err.println("Update check failed: " + ex.getMessage());
                // Continue with normal application startup anyway
                launchApp();
                return null;
            });
    }

    private static void launchApp() {
        // Your application startup code here
    }

    private static void cleanup() {
        // Save user data, flush logs, release resources, etc.
    }
}
```

### Synchronous API (Blocking)

If you prefer a synchronous approach:

```java
try {
    UpdateResult result = client.requireVersion("2.0.0", params);

    if (result.isRequired()) {
        result.launchInstaller();
        cleanup();
        System.exit(0);
    } else {
        launchApp();
    }
} catch (IOException e) {
    System.err.println("Update check failed: " + e.getMessage());
    launchApp();
}
```

## UpdateParameters Configuration

The `UpdateParameters` class provides all metadata needed for update checking without requiring local files like `package.json`.

### Required Parameters

- **packageName** - Your package identifier (e.g., "my-app" or "@scope/my-app")

### Optional Parameters

- **source** - Controls which backend to use:
  - GitHub-hosted: `"https://github.com/username/repo"`
  - npm-hosted: Leave empty `""` or omit entirely
- **appTitle** - Human-friendly name shown in UI prompts (defaults to packageName)
- **currentVersion** - Currently installed version (defaults to `jdeploy.app.version` system property)

### Builder Example

```java
UpdateParameters params = new UpdateParameters.Builder("snapcode")
    .source("https://github.com/shannah/snapcode")
    .appTitle("SnapCode")
    .currentVersion("1.2.3")
    .build();
```

## Update Flow

1. **Check for updates** - The client checks if the required launcher version is available
2. **Prompt user** (GUI mode) - Shows a Swing dialog with options:
   - **Update Now** - Downloads and installs the new launcher
   - **Later** - Defers the update to next launch
   - **Ignore** - Remembers to skip this version
3. **Headless mode** - Automatically defers (returns `isRequired() == false`)
4. **Launch installer** - If user chooses "Update Now", call `result.launchInstaller()`
5. **Cleanup and exit** - Perform any necessary cleanup, then exit your app

## User Preferences

The library automatically manages user preferences:

- **Ignored versions** - If a user clicks "Ignore", that version won't prompt again
- **Persistent storage** - Uses Java Preferences API to remember user choices

## Headless Environment

In headless environments (no GUI available), the update client:

- Skips the user prompt
- Returns `isRequired() == false`
- Allows the application to continue normally

## Advanced: Manual Update Controller

For custom UI implementations or integration with existing update mechanisms:

```java
import ca.weblite.jdeploy.updateclient.ManualUpdateController;

ManualUpdateController controller = new ManualUpdateController(params);

// Check if update is available (without prompting)
if (controller.isUpdateAvailable("2.0.0")) {
    // Show your custom UI
    boolean userWantsUpdate = showCustomUpdateDialog();

    if (userWantsUpdate) {
        controller.launchInstaller();
        System.exit(0);
    }
}
```

## Requirements

- **Java 8 or higher**
- **Swing** (for GUI prompts)
- No external dependencies (minimal-json is shaded)

## License

Apache License 2.0

## Contributing

Contributions are welcome! Please submit issues and pull requests to the [GitHub repository](https://github.com/shannah/jdeploy-update-client).

## Support

For questions or issues:
- Open an issue on [GitHub](https://github.com/shannah/jdeploy-update-client/issues)
- Check the [jDeploy documentation](https://www.jdeploy.com/docs)