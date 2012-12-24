/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import junit.framework.TestCase;

/**
 * Tests that valid CSS identifiers without non-ASCII characters or escaped characters are unchanged, and that invalid
 * ones are changed as expected.
 */
public class FilterCSSIdentifierTest extends TestCase {
	public void testKnownValid() {
		String identifiers[] = { "sample_key-1", "-_", "-k_d", "_testing-key" };

		for (String identifier : identifiers) {
			assertEquals(identifier, PageMaker.filterCSSIdentifier(identifier));
		}
	}

	public void testInvalidFirstDash() {
		assertEquals("-_things", PageMaker.filterCSSIdentifier("-9things"));
		assertEquals("-_", PageMaker.filterCSSIdentifier("--"));
	}

	public void testInvalidChar() {
		assertEquals("__thing", PageMaker.filterCSSIdentifier("#$thing"));
	}
}
