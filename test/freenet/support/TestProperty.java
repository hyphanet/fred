/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
** Unified class for getting java properties to control unit test behaviour.
**
** @author infinity0
*/
final public class TestProperty {

	private TestProperty() { }

	final public static boolean BENCHMARK = Boolean.getBoolean("test.benchmark");
	final public static boolean VERBOSE = Boolean.getBoolean("test.verbose");
	final public static boolean EXTENSIVE = Boolean.getBoolean("test.extensive");
	final public static String L10nPath_test= System.getProperty("test.l10npath_test", "../test/freenet/l10n/");
	final public static String L10nPath_main= System.getProperty("test.l10npath_main", "freenet/l10n/");

}
