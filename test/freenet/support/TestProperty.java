/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import org.junit.Test;

/**
** Unified class for getting java properties to control unit test behaviour.
**
** @author infinity0
*/
final public class TestProperty {

	public TestProperty() { }

	final public static boolean BENCHMARK = Boolean.getBoolean("test.benchmark");
	final public static boolean VERBOSE = Boolean.getBoolean("test.verbose");
	final public static boolean EXTENSIVE = Boolean.getBoolean("test.extensive");

	/** Useful to check whether things such as Travis CI properly pass properties */
	@Test
	public void printProperties() {
		System.out.println("test.benchmark=" + BENCHMARK);
		System.out.println("test.verbose=" + VERBOSE);
		System.out.println("test.extensive=" + EXTENSIVE);
	}
}
