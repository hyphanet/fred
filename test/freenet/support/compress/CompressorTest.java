/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.compress;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.compress.Compressor} class.
 * 
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class CompressorTest extends TestCase {

	/**
	 * test abstract class and accessors for logical consistency
	 */
	public void testCompressor(){
		
		// force us to notice when we modify the number of supported compressors
		int algos = Compressor.countCompressAlgorithms();
		assertEquals(1, algos);
		
		for(int i = 0; i < algos; i++){
			Compressor compressorByDifficulty = 
				Compressor.getCompressionAlgorithmByDifficulty(i);        // FIXME: int vs. short
			Compressor compressorByMetadataId = 
				Compressor.getCompressionAlgorithmByMetadataID((short)i); // FIXME: int vs. short
			
			// check the codec number equals the index into the algorithm list
			assertEquals(i,compressorByDifficulty.codecNumberForMetadata());
			
			// check that the compressor obtained by difficulty index is the same 
			// as the compressor obtained by metadata id
			assertEquals(compressorByDifficulty, compressorByMetadataId);
		}
	}
	
}
