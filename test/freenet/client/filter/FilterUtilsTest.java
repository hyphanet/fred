package freenet.client.filter;

import junit.framework.TestCase;

public class FilterUtilsTest extends TestCase {
	public void testValidLenthUnits() {
		// Test all valid length units for CSS and valid values
		assertTrue(FilterUtils.isLength("1em", false));
		assertTrue(FilterUtils.isLength("1.12em", false));
		assertTrue(FilterUtils.isLength("-1e-12em", false));
		assertTrue(FilterUtils.isLength("1E+12em", false));
		assertTrue(FilterUtils.isLength("1.1vw", false));
		assertTrue(FilterUtils.isLength("1.1vh", false));
		assertTrue(FilterUtils.isLength("1.1rem", false));
		assertTrue(FilterUtils.isLength("1.1px", false));
		assertTrue(FilterUtils.isLength("1.1mm", false));
		assertTrue(FilterUtils.isLength("1.1cm", false));
		assertTrue(FilterUtils.isLength(".11cm", false));
		assertTrue(FilterUtils.isLength("+1.1ch", false));
		assertTrue(FilterUtils.isLength("-1.1vmin", false));
		assertTrue(FilterUtils.isLength("-1.1vmax", false));
		assertTrue(FilterUtils.isLength("1.em", false));
		assertTrue(FilterUtils.isLength("0", false));
		assertTrue(FilterUtils.isLength("0.0", false));
		assertTrue(FilterUtils.isLength("81", true));
		assertTrue(FilterUtils.isLength("5.1%", true));
		assertTrue(FilterUtils.isLength("1", true));
		assertTrue(FilterUtils.isLength("1.em", true));
		assertTrue(FilterUtils.isLength("1.", true));
	}

	public void testInvalidLengthUnits() {
		assertFalse(FilterUtils.isLength("--1.1em", false));
		assertFalse(FilterUtils.isLength("-1f-1vmax", false));
		assertFalse(FilterUtils.isLength("-1.1vmx", false));
		assertFalse(FilterUtils.isLength("-11vmem", false));
		assertFalse(FilterUtils.isLength("--1.1vmax", false));
		assertFalse(FilterUtils.isLength("sevenvmax", false));
		assertFalse(FilterUtils.isLength("1", false));
		assertFalse(FilterUtils.isLength("1.", false));
		assertFalse(FilterUtils.isLength("", false));
	}
}
