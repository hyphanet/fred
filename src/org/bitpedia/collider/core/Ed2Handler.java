/* (PD) 2006 The Bitzi Corporation
 * Please see http://bitzi.com/publicdomain for more info.
 *
 * $Id: Ed2Handler.java,v 1.2 2006/07/14 04:58:39 gojomo Exp $
 */
package org.bitpedia.collider.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Ed2Handler extends MessageDigest {
	
	private static final int EDSEG_SIZE = 1024*9500;
	
	private MessageDigest seg;   // the current 9,216,000 byte block
	private MessageDigest top;   // the total file value
	private long nextPos;

	public Ed2Handler() {
		super("ED2K");
		analyzeInit();
	}
	
	public void analyzeInit() {
		
		nextPos = 0;
		
		try {
			seg = MessageDigest.getInstance("MD4");
			top = MessageDigest.getInstance("MD4");
		} catch (NoSuchAlgorithmException e) {
			throw new Error("MD4 not supported?!");
		}
		
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
			byte[] innerDigest = seg.digest();
			// feed it to the overall hash
			top.update(innerDigest, 0, 16);
			// reset the current segment
			seg.reset();
		}

	    // now, handle the new data
		if ((nextPos/EDSEG_SIZE) == (nextPos+inputLen)/EDSEG_SIZE) {
			// not finishing any segments, just keep feeding segment hash
			seg.update(input, ofs, inputLen);
			nextPos += inputLen;
			return;
		}
		// OK, we're reaching or crossing a segment-end

		// finish the current segment
		int firstLen = EDSEG_SIZE - (int)(nextPos % EDSEG_SIZE);
		seg.update(input, ofs, firstLen);
		nextPos += firstLen;

		// continue with passed-in info
		analyzeUpdate(input, ofs+firstLen, inputLen-firstLen);
	}

	public byte[] analyzeFinal() {
		
	    if(nextPos <= EDSEG_SIZE) {
			// there was only one segment; return its hash
	    	return seg.digest();
		}

	    // finish the segment in process
	    byte[] innerDigest = seg.digest();
	    // feed it to the overall hash
		top.update(innerDigest, 0, 16);
		
	    return top.digest();
	}

	@Override
	protected byte[] engineDigest() {
		return analyzeFinal();
	}

	@Override
	protected void engineReset() {
		analyzeInit();
	}

	@Override
	protected void engineUpdate(byte arg0) {
		engineUpdate(new byte[] { arg0 }, 0, 1);
	}

	@Override
	protected void engineUpdate(byte[] arg0, int arg1, int arg2) {
		analyzeUpdate(arg0, arg1, arg2);
	}
	
	@Override
	protected int engineGetDigestLength() {
		return 16;
	}

}
