package ca.weblite.jdeploy.updateclient;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * Unit tests for installer-related helper methods in UpdateClient.
 *
 * <p>- macOS-only: verifies findFirstAppBundle() finds a nested .app directory. - Linux-only:
 * verifies decompressGzipInstaller() decompresses a .gz file to a non-empty file.
 *
 * <p>These tests use reflection to access the private helper methods under test and use platform
 * guards (Assume) to skip tests on unsupported OSes.
 */
public class UpdateClientInstallerTest {

  /**
   * macOS-only: create a temp tree containing a nested TestApp.app directory; assert
   * findFirstAppBundle() locates it.
   */
  @Test
  public void testFindFirstAppBundle_mac() throws Exception {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
    Assume.assumeTrue("Test only runs on macOS", os.contains("mac") || os.contains("darwin"));

    Path tempDir = Files.createTempDirectory("updateclient-test-mac-");
    try {
      // Create nested directories with a .app bundle
      Path nestedApp = tempDir.resolve("nested").resolve("deep").resolve("TestApp.app");
      Files.createDirectories(nestedApp.resolve("Contents").resolve("MacOS"));

      UpdateClient client = new UpdateClient();

      // Access private method findFirstAppBundle(Path)
      Method m = UpdateClient.class.getDeclaredMethod("findFirstAppBundle", Path.class);
      m.setAccessible(true);
      Path found = (Path) m.invoke(client, tempDir);

      Assert.assertNotNull("Expected to find a .app bundle", found);
      Assert.assertTrue(
          "Found path should end with TestApp.app",
          found.getFileName().toString().toLowerCase().endsWith(".app"));
    } finally {
      deleteRecursively(tempDir);
    }
  }

  /**
   * Linux-only: create a small gzip file in temp and verify decompressGzipInstaller() produces a
   * non-empty decompressed file.
   */
  @Test
  public void testDecompressGzipInstaller_linux() throws Exception {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
    Assume.assumeTrue("Test only runs on Linux", os.contains("linux"));

    Path tempDir = Files.createTempDirectory("updateclient-test-linux-");
    try {
      // Create a small gzip file with some content
      Path gzFile = tempDir.resolve("small-test.bin.gz");
      try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(gzFile.toFile()))) {
        gos.write("hello, world".getBytes());
        gos.finish();
      }

      UpdateClient client = new UpdateClient();

      // Access private method decompressGzipInstaller(Path)
      Method m = UpdateClient.class.getDeclaredMethod("decompressGzipInstaller", Path.class);
      m.setAccessible(true);
      Path decompressed = (Path) m.invoke(client, gzFile);

      Assert.assertNotNull("Decompressed path should not be null", decompressed);
      Assert.assertTrue("Decompressed file should exist", Files.exists(decompressed));
      Assert.assertTrue("Decompressed file should be non-empty", Files.size(decompressed) > 0);
    } finally {
      deleteRecursively(tempDir);
    }
  }

  // Helper to recursively delete a directory tree (best-effort)
  private static void deleteRecursively(Path root) {
    if (root == null) return;
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              try {
                Files.deleteIfExists(file);
              } catch (Exception ignored) {
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              try {
                Files.deleteIfExists(dir);
              } catch (Exception ignored) {
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
    }
  }
}
