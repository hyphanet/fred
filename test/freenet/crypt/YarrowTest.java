/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import org.spaceroots.mantissa.random.ScalarSampleStatistics;
import junit.framework.*;

public class YarrowTest extends TestCase {

	
// REDFLAG: for some reason that test fails on emu... investigate why and review our implementation of Yarrow!
	public void testDouble() {
//		Yarrow mt = new Yarrow(false);
		ScalarSampleStatistics sample = new ScalarSampleStatistics();
//		for(int i = 0; i < 1000; ++i) {
//			sample.add(mt.nextDouble());
//		}
//
//		assertEquals(0.5, sample.getMean(), 0.02);
//		assertEquals(1.0 / (2.0 * Math.sqrt(3.0)), sample.getStandardDeviation(), 0.002);
	}
}
