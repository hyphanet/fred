/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * A class to compute CRCs complying with ISO 3309
 * It's used in the PNG filter.
 * 
 * @author nextgens
 */
public class CRC {
	public static final long[] CRC_TABLE = new long[256];
	public static final long PNG_POLYNOMINAL = 0xedb88320L;
	
	static {
		long c;
		for(int i=0; i<256; i++) {
			c = i;
			for(int j=0; j<8; j++) {
				if(0 != (c & 1))
					c = PNG_POLYNOMINAL^(c >> 1);
				else
					c = c >> 1;
			}
			CRC_TABLE[i] = c;
		}
	}
	
	private static long update_crc(long crc, byte[] buf) {
		// it can't be above 2^31-1 anyway... hence we use an int
		if(buf.length > Integer.MAX_VALUE)
			throw new IllegalArgumentException("The buffer is too big!");
		
		for (int i = 0; i < buf.length; i++)
			crc = CRC_TABLE[(int)(crc ^ buf[i]) & 0xff] ^ (crc >> 8);
		
		return crc;
	}
	
	public static long crc(byte[] input) {
		return update_crc(0xffffffffL, input) ^ 0xffffffffL;
	}
}
