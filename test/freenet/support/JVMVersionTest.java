package freenet.support;

import junit.framework.Assert;
import junit.framework.TestCase;

public class JVMVersionTest extends TestCase {

	public void testTooOld() {
		Assert.assertTrue(JVMVersion.isTooOld("1.6.0_32"));
		Assert.assertTrue(JVMVersion.isTooOld("1.6"));
		Assert.assertTrue(JVMVersion.isTooOld("1.5"));
	}

	public void testRecentEnough() {
		Assert.assertFalse(JVMVersion.isTooOld("1.7.0_65"));
		Assert.assertFalse(JVMVersion.isTooOld("1.7"));
		Assert.assertFalse(JVMVersion.isTooOld("1.8.0_9"));
		Assert.assertFalse(JVMVersion.isTooOld("1.10"));
	}

	public void testNull() {
		Assert.assertFalse(JVMVersion.isTooOld(null));
	}

	public void testCompare() {
	    String[] orderedVersions = new String[] {
	        "1.7.bogus", // Bogus versions are treated as 0.0.0_0
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
	        "1.10",
	        "1.10.1"
	    };

	    // Compare all combinations and check correctness of their ordering
	    for (int i = 0; i < orderedVersions.length; i++) {
	        String v1 = orderedVersions[i];
	        for (int j = 0; j < orderedVersions.length; j++) {
	            String v2 = orderedVersions[j];
	            int expected = Integer.signum(Integer.valueOf(i).compareTo(Integer.valueOf(j)));
	            int actual = Integer.signum(JVMVersion.compareVersion(v1, v2));
	            assertEquals(expected, actual);
	        }
	    }
	}
}
