package freenet.support;

import static org.junit.Assert.*;

import org.junit.Test;

public class JVMVersionTest {

	@Test
	public void testTooOldWarning() {
		assertTrue(JVMVersion.isEOL("1.6.0_32"));
		assertTrue(JVMVersion.isEOL("1.6"));
		assertTrue(JVMVersion.isEOL("1.5"));
		assertTrue(JVMVersion.isEOL("1.7.0_65"));
		assertTrue(JVMVersion.isEOL("1.7"));
	}

	@Test
	public void testTooOldUpdater() {
		assertTrue(JVMVersion.needsLegacyUpdater("1.6.0_32"));
		assertTrue(JVMVersion.needsLegacyUpdater("1.6"));
		assertTrue(JVMVersion.needsLegacyUpdater("1.5"));
		assertTrue(JVMVersion.needsLegacyUpdater("1.7.0_65"));
		assertTrue(JVMVersion.needsLegacyUpdater("1.7"));
	}

	@Test
	public void testRecentEnoughWarning() {
		assertFalse(JVMVersion.isEOL("1.8.0_9"));
		assertFalse(JVMVersion.isEOL("9-ea"));
		assertFalse(JVMVersion.isEOL("10"));
	}

	@Test
	public void testRecentEnoughUpdater() {
		assertFalse(JVMVersion.needsLegacyUpdater("1.8.0_9"));
		assertFalse(JVMVersion.needsLegacyUpdater("9-ea"));
		assertFalse(JVMVersion.needsLegacyUpdater("10"));
	}

	@Test
	public void testRelative() {
		/*
		 * Being at too old a version for the modern updater URI must produce a warning, but a warning can be shown for
		 * a version not yet too old for the modern updater URI.
		 */
		assertTrue(JVMVersion.compareVersion(JVMVersion.UPDATER_THRESHOLD, JVMVersion.EOL_THRESHOLD) <= 0);
	}

	@Test
	public void testNull() {
		assertFalse(JVMVersion.isEOL(null));
		assertFalse(JVMVersion.needsLegacyUpdater(null));
	}

	@Test
	public void testCompare() {
	    String[] orderedVersions = new String[] {
	        "bogus", // Bogus versions are treated as 0.0.0_0
	        "1.5",
	        "1.6.0",
	        "1.6.0_32",
	        "1.7",
	        "1.7.0_59",
	        "1.7.0_65",
	        "1.7.1",
	        "1.7.1_1",
	        "1.7.1_09",
	        "1.7.1_65-rc4",
	        "1.7.2-ea",
	        "1.7.3_0",
	        "1.8-beta",
	        "9-ea",
	        "9.0.1.0",
	        "9.0.1.1.0.1-ea",
	        "9.2",
	        "10",
	        "10.0.2"
	    };

	    // Compare all combinations and check correctness of their ordering
	    for (int i = 0; i < orderedVersions.length; i++) {
	        String v1 = orderedVersions[i];
	        for (int j = 0; j < orderedVersions.length; j++) {
	            String v2 = orderedVersions[j];
	            int expected = Integer.signum(Integer.compare(i, j));
	            int actual = Integer.signum(JVMVersion.compareVersion(v1, v2));
	            assertEquals(v1 + " <> " + v2, expected, actual);
	        }
	    }
	}
}
