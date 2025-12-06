package ca.weblite.jdeploy.updateclient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Unit tests for UpdateClient pure logic (private methods invoked via reflection).
 */
public class UpdateClientTest {

    private String oldOsName;
    private String oldOsArch;

    @Before
    public void setUp() {
        oldOsName = System.getProperty("os.name");
        oldOsArch = System.getProperty("os.arch");
    }

    @After
    public void tearDown() {
        // restore original system properties
        if (oldOsName != null) {
            System.setProperty("os.name", oldOsName);
        } else {
            System.clearProperty("os.name");
        }
        if (oldOsArch != null) {
            System.setProperty("os.arch", oldOsArch);
        } else {
            System.clearProperty("os.arch");
        }
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test
    public void testCompareVersionNumericOrdering() throws Exception {
        UpdateClient client = new UpdateClient();
        Method compare = UpdateClient.class.getDeclaredMethod("compareVersion", String.class, String.class);
        compare.setAccessible(true);

        assertEquals(-1, (int) compare.invoke(client, "1.2.0", "1.2.1"));
        assertEquals(1, (int) compare.invoke(client, "1.2.10", "1.2.2"));
        assertEquals(0, (int) compare.invoke(client, "1.0.0", "1.0.0"));
        // branch vs normal: numeric comparison should make branch lower than higher numeric versions
        assertTrue(((int) compare.invoke(client, "0.0.0-branch", "1.0.0")) < 0);
    }

    @Test
    public void testCompareVersionPrereleasePrecedence() throws Exception {
        UpdateClient client = new UpdateClient();
        Method compare = UpdateClient.class.getDeclaredMethod("compareVersion", String.class, String.class);
        compare.setAccessible(true);

        // non-prerelease > prerelease
        assertEquals(1, (int) compare.invoke(client, "1.0.0", "1.0.0-alpha"));

        // prerelease lexicographic comparison
        assertTrue(((int) compare.invoke(client, "1.0.0-alpha", "1.0.0-beta")) < 0);

        // equal prerelease strings
        assertEquals(0, (int) compare.invoke(client, "2.1.0-rc.1", "2.1.0-rc.1"));
    }

    @Test
    public void testIsPrereleaseAndIsBranchVersion() throws Exception {
        UpdateClient client = new UpdateClient();
        Method isPrerelease = UpdateClient.class.getDeclaredMethod("isPrerelease", String.class);
        isPrerelease.setAccessible(true);

        Method isBranch = UpdateClient.class.getDeclaredMethod("isBranchVersion", String.class);
        isBranch.setAccessible(true);

        // prerelease
        assertTrue((Boolean) isPrerelease.invoke(client, "1.0.0-alpha"));
        // branch versions should not count as prerelease
        assertTrue((Boolean) isBranch.invoke(client, "0.0.0-feature-xyz"));
        assertFalse((Boolean) isPrerelease.invoke(client, "0.0.0-feature-xyz"));

        // normal versions
        assertFalse((Boolean) isPrerelease.invoke(client, "1.0.0"));
        assertFalse((Boolean) isBranch.invoke(client, "1.0.0-beta")); // not starting with 0.0.0-
    }

    @Test
    public void testExtractFilenameFromHeader() throws Exception {
        UpdateClient client = new UpdateClient();
        Method extract = UpdateClient.class.getDeclaredMethod("extractFilenameFromHeader", String.class);
        extract.setAccessible(true);

        String h1 = "attachment; filename=MyApp-1.0.0_ABC.tar.gz";
        assertEquals("MyApp-1.0.0_ABC.tar.gz", (String) extract.invoke(client, h1));

        String h2 = "attachment; filename=\"My App.pkg\"";
        assertEquals("My App.pkg", (String) extract.invoke(client, h2));

        String h3 = "attachment; filename*=UTF-8''rates.tar.gz";
        assertEquals("rates.tar.gz", (String) extract.invoke(client, h3));

        assertEquals("", (String) extract.invoke(client, (Object) null));
        assertEquals("", (String) extract.invoke(client, ""));
    }

    @Test
    public void testDetectPlatformAndConstructURL_Windows() throws Exception {
        // Simulate Windows x64
        System.setProperty("os.name", "Windows 10");
        System.setProperty("os.arch", "amd64");

        UpdateClient client = new UpdateClient();

        // invoke detectPlatformAndFormat (private)
        Method detect = UpdateClient.class.getDeclaredMethod("detectPlatformAndFormat");
        detect.setAccessible(true);
        Object platformInfo = detect.invoke(client);
        assertNotNull(platformInfo);

        // platformInfo is a private static inner class; reflect fields
        Field platformField = platformInfo.getClass().getDeclaredField("platform");
        platformField.setAccessible(true);
        Field formatField = platformInfo.getClass().getDeclaredField("format");
        formatField.setAccessible(true);

        String platform = (String) platformField.get(platformInfo);
        String format = (String) formatField.get(platformInfo);

        assertEquals("win", platform);
        assertEquals("exe", format);

        // test URL construction uses platform and format
        Method constructUrl = UpdateClient.class.getDeclaredMethod("constructInstallerDownloadURL", String.class, String.class, String.class);
        constructUrl.setAccessible(true);

        String url = (String) constructUrl.invoke(client, "mypkg", "1.2.3", "https://github.com/owner/repo");
        assertNotNull(url);
        assertTrue(url.contains("platform=win"));
        assertTrue(url.contains("format=exe"));
        assertTrue(url.contains("package=mypkg"));
        assertTrue(url.contains("version=1.2.3"));
        assertTrue(url.contains("source=")); // encoded source present
        assertTrue(url.contains("prerelease=false"));
        assertTrue(url.contains("updates=latest"));
    }

    @Test
    public void testDetectPlatformAndConstructURL_mac_arm64() throws Exception {
        // Simulate macOS arm64
        System.setProperty("os.name", "Mac OS X");
        System.setProperty("os.arch", "aarch64");

        UpdateClient client = new UpdateClient();

        Method detect = UpdateClient.class.getDeclaredMethod("detectPlatformAndFormat");
        detect.setAccessible(true);
        Object platformInfo = detect.invoke(client);
        assertNotNull(platformInfo);

        Field platformField = platformInfo.getClass().getDeclaredField("platform");
        platformField.setAccessible(true);
        Field formatField = platformInfo.getClass().getDeclaredField("format");
        formatField.setAccessible(true);

        String platform = (String) platformField.get(platformInfo);
        String format = (String) formatField.get(platformInfo);

        assertEquals("mac-arm64", platform);
        assertEquals("", format);

        Method constructUrl = UpdateClient.class.getDeclaredMethod("constructInstallerDownloadURL", String.class, String.class, String.class);
        constructUrl.setAccessible(true);
        String url = (String) constructUrl.invoke(client, "pkg", "0.1.0", "");
        assertTrue(url.contains("platform=mac-arm64"));
    }
}
