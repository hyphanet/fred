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
	}

	public void testNull() {
		Assert.assertFalse(JVMVersion.isTooOld(null));
	}
}
