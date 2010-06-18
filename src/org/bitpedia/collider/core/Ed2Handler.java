/* (PD) 2006 The Bitzi Corporation
 * Please see http://bitzi.com/publicdomain for more info.
 *
 * $Id: Ed2Handler.java,v 1.2 2006/07/14 04:58:39 gojomo Exp $
 */
package org.bitpedia.collider.core;

public class Ed2Handler {
	
	private static final int EDSEG_SIZE = 1024*9500;
	
	private Md4Handler seg;   // the current 9,216,000 byte block
	private Md4Handler top;   // the total file value
	private long nextPos;

	public void analyzeInit() {
		
		nextPos = 0;
		
		seg = new Md4Handler();
		seg.analyzeInit();
		
		top = new Md4Handler();
		top.analyzeInit();
	}
	
	public void analyzeUpdate(byte[] input, int inputLen) {
		
		analyzeUpdate(input, 0, inputLen);
	}

	public void analyzeUpdate(byte[] input, int ofs, int inputLen) {
		
	    // first, do no harm
		if (0 == inputLen) return;

		// now, close up any segment that's been completed
		if((0 < nextPos) && (0 == (nextPos % EDSEG_SIZE))) {
			// finish
			byte[] innerDigest = seg.analyzeFinal();
			// feed it to the overall hash
			top.analyzeUpdate(innerDigest,16);
			// reset the current segment
			seg.analyzeInit();
		}

	    // now, handle the new data
		if ((nextPos/EDSEG_SIZE) == (nextPos+inputLen)/EDSEG_SIZE) {
			// not finishing any segments, just keep feeding segment hash
			seg.analyzeUpdate(input, ofs, inputLen);
			nextPos += inputLen;
			return;
		}
		// OK, we're reaching or crossing a segment-end

		// finish the current segment
		int firstLen = EDSEG_SIZE - (int)(nextPos % EDSEG_SIZE);
		seg.analyzeUpdate(input, ofs, firstLen);
		nextPos += firstLen;

		// continue with passed-in info
		analyzeUpdate(input, ofs+firstLen, inputLen-firstLen);
	}

	public byte[] analyzeFinal() {
		
	    if(nextPos <= EDSEG_SIZE) {
			// there was only one segment; return its hash
	    	return seg.analyzeFinal();
		}

	    // finish the segment in process
	    byte[] innerDigest = seg.analyzeFinal();
	    // feed it to the overall hash
		top.analyzeUpdate(innerDigest, 16);
		
	    return top.analyzeFinal();
	}

}
