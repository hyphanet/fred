package freenet.client.filter;

import static org.junit.Assert.*;

import org.junit.Test;

public class FilterUtilsTest {
	@Test
	public void testValidLengthUnits() {
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

	@Test
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
	
	@Test
	public void testValidColors() {
		assertTrue(FilterUtils.isColor("rebeccapurple"));
		assertTrue(FilterUtils.isColor("Transparent"));
		assertTrue(FilterUtils.isColor("WindowText"));
		assertTrue(FilterUtils.isColor("#123ABC"));
		assertTrue(FilterUtils.isColor("#123"));
		assertTrue(FilterUtils.isColor("#123F"));
		assertTrue(FilterUtils.isColor("#123456ff"));
		assertTrue(FilterUtils.isColor("rgb(0,10,255)"));
		assertTrue(FilterUtils.isColor("rgb(0 10 255)"));
		assertTrue(FilterUtils.isColor("rgba(100 200 255 / 0.25)"));
		assertTrue(FilterUtils.isColor("rgba(010 00200 255 / 25%)"));
		assertTrue(FilterUtils.isColor("rgba(none 0 0% /)"));
	}
	
	@Test
	public void testInvalidColors() {
		assertFalse(FilterUtils.isColor("rgb(0.1 0.2 0.3)")); // should be between 0 and 255, not 0.1
		assertFalse(FilterUtils.isColor("rgb(")); // completely empty; don't trigger out of range access here!
		assertFalse(FilterUtils.isColor("rgb()")); // completely empty; don't trigger out of range access here!
		assertFalse(FilterUtils.isColor("rgb(/)")); // completely empty; don't trigger out of range access here!
		assertFalse(FilterUtils.isColor("#ABCDEFGH")); // #ABCDEF followed by G and H
		assertFalse(FilterUtils.isColor("112233")); // missing #
		assertFalse(FilterUtils.isColor("#12")); // not #RGB
		assertFalse(FilterUtils.isColor("#12345")); // not #RGBA neither #RRGGBB
		assertFalse(FilterUtils.isColor("#1234567")); // not #RRGGBB neither #RRGGBBAA
		assertFalse(FilterUtils.isColor("url(/KSK@foo)")); // url not color!
	}
}
