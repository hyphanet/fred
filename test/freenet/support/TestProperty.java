/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 ** Unified class for getting java properties to control unit test behaviour.
 **
 ** @author infinity0
 */
public final class TestProperty {

	private TestProperty() {}

	public static final boolean BENCHMARK = Boolean.getBoolean(
		"test.benchmark"
	);
	public static final boolean VERBOSE = Boolean.getBoolean("test.verbose");
	public static final boolean EXTENSIVE = Boolean.getBoolean(
		"test.extensive"
	);
	public static final String L10nPath_test = System.getProperty(
		"test.l10npath_test",
		"../test/freenet/l10n/"
	);
	public static final String L10nPath_main = System.getProperty(
		"test.l10npath_main",
		"freenet/l10n/"
	);
}
