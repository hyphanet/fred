/* (PD) 2006 The Bitzi Corporation
 * Please see http://bitzi.com/publicdomain for more info.
 *
 * $Id: Md4Handler.java,v 1.2 2006/07/14 04:58:39 gojomo Exp $
 */
package org.bitpedia.collider.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class Md4Handler {

    // Constants for MD4Transform routine.
	private static final int S11 = 3;
	private static final int S12 = 7;
	private static final int S13 = 11;
	private static final int S14 = 19;
	private static final int S21 = 3;
	private static final int S22 = 5;
	private static final int S23 = 9;
	private static final int S24 = 13;
	private static final int S31 = 3;
	private static final int S32 = 9;
	private static final int S33 = 11;
	private static final int S34 = 15;
	
	private static byte P0 = -128; //0x80 
	
	private static byte[] PADDING = {
	  P0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 
	  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 
	  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 
	  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
	
	private int[] state; /* state (ABCD) */
	private int[] count; /* number of bits, modulo 2^64 (lsb first) */
	private byte[] buffer;	
	
	private static int F(int x, int y, int z) {
		return (((x) & (y)) | ((~x) & (z)));
	}
	private static int G(int x, int y, int z) {
		return (((x) & (y)) | ((x) & (z)) | ((y) & (z)));
	}
	private static int H(int x, int y, int z) {
		return ((x) ^ (y) ^ (z));
	}
	private static int rotateLeft(int x, int n) {
		return (((x) << (n)) | ((x) >>> (32-(n))));
	}

	private static int FF(int a, int b, int c, int d, int x, int s) {
	    a += F (b, c, d) + x; 
	    return rotateLeft(a, s); 
	}
	private static int GG(int a, int b, int c, int d, int x, int s) { 
	    a += G(b, c, d) + x + 0x5a827999; 
	    return rotateLeft(a, s); 
	}
	private static int HH(int a, int b, int c, int d, int x, int s) { 
	    a += H(b, c, d) + x + 0x6ed9eba1; 
	    return rotateLeft(a, s); 
	}

	/* Encodes input (int[]) into output (byte[]). 
	 * Assumes len is a multiple of 4. */
	private static void encode(byte[] output, int[] input, int len) {
		
		ByteBuffer buf = ByteBuffer.allocate(len);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		IntBuffer intBuf = buf.asIntBuffer();
		intBuf.put(input, 0, len / 4);
		buf.get(output, 0, len);
	}

	/* Decodes input (unsigned char) into output (w32). 
	 * Assumes len is a multiple of 4. */
	private static void decode(int[] output, byte[] input, int ofs, int len) {
		
		ByteBuffer buf = ByteBuffer.wrap(input, ofs, len);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		IntBuffer intBuf = buf.asIntBuffer();
		intBuf.get(output, 0, len / 4);
	} 	
	
	private static void md4Transform(int[] state, byte[] block, int ofs) {
		
	  int a = state[0], b = state[1], c = state[2], d = state[3]; 
	  int[] x = new int[16];
	  
	  decode(x, block, ofs, 64); 

	  /* Round 1 */
	  a = FF (a, b, c, d, x[ 0], S11); /* 1 */
	  d = FF (d, a, b, c, x[ 1], S12); /* 2 */
	  c = FF (c, d, a, b, x[ 2], S13); /* 3 */
	  b = FF (b, c, d, a, x[ 3], S14); /* 4 */
	  a = FF (a, b, c, d, x[ 4], S11); /* 5 */
	  d = FF (d, a, b, c, x[ 5], S12); /* 6 */
	  c = FF (c, d, a, b, x[ 6], S13); /* 7 */
	  b = FF (b, c, d, a, x[ 7], S14); /* 8 */
	  a = FF (a, b, c, d, x[ 8], S11); /* 9 */
	  d = FF (d, a, b, c, x[ 9], S12); /* 10 */
	  c = FF (c, d, a, b, x[10], S13); /* 11 */
	  b = FF (b, c, d, a, x[11], S14); /* 12 */
	  a = FF (a, b, c, d, x[12], S11); /* 13 */
	  d = FF (d, a, b, c, x[13], S12); /* 14 */
	  c = FF (c, d, a, b, x[14], S13); /* 15 */
	  b = FF (b, c, d, a, x[15], S14); /* 16 */

	  /* Round 2 */
	  a = GG (a, b, c, d, x[ 0], S21); /* 17 */
	  d = GG (d, a, b, c, x[ 4], S22); /* 18 */
	  c = GG (c, d, a, b, x[ 8], S23); /* 19 */
	  b = GG (b, c, d, a, x[12], S24); /* 20 */
	  a = GG (a, b, c, d, x[ 1], S21); /* 21 */
	  d = GG (d, a, b, c, x[ 5], S22); /* 22 */
	  c = GG (c, d, a, b, x[ 9], S23); /* 23 */
	  b = GG (b, c, d, a, x[13], S24); /* 24 */
	  a = GG (a, b, c, d, x[ 2], S21); /* 25 */
	  d = GG (d, a, b, c, x[ 6], S22); /* 26 */
	  c = GG (c, d, a, b, x[10], S23); /* 27 */
	  b = GG (b, c, d, a, x[14], S24); /* 28 */
	  a = GG (a, b, c, d, x[ 3], S21); /* 29 */
	  d = GG (d, a, b, c, x[ 7], S22); /* 30 */
	  c = GG (c, d, a, b, x[11], S23); /* 31 */
	  b = GG (b, c, d, a, x[15], S24); /* 32 */

	  /* Round 3 */
	  a = HH (a, b, c, d, x[ 0], S31); /* 33 */
	  d = HH (d, a, b, c, x[ 8], S32); /* 34 */
	  c = HH (c, d, a, b, x[ 4], S33); /* 35 */
	  b = HH (b, c, d, a, x[12], S34); /* 36 */
	  a = HH (a, b, c, d, x[ 2], S31); /* 37 */
	  d = HH (d, a, b, c, x[10], S32); /* 38 */
	  c = HH (c, d, a, b, x[ 6], S33); /* 39 */
	  b = HH (b, c, d, a, x[14], S34); /* 40 */
	  a = HH (a, b, c, d, x[ 1], S31); /* 41 */
	  d = HH (d, a, b, c, x[ 9], S32); /* 42 */
	  c = HH (c, d, a, b, x[ 5], S33); /* 43 */
	  b = HH (b, c, d, a, x[13], S34); /* 44 */
	  a = HH (a, b, c, d, x[ 3], S31); /* 45 */
	  d = HH (d, a, b, c, x[11], S32); /* 46 */
	  c = HH (c, d, a, b, x[ 7], S33); /* 47 */
	  b = HH (b, c, d, a, x[15], S34); /* 48 */

	  state[0] += a;
	  state[1] += b;
	  state[2] += c;
	  state[3] += d;
	}
	
	public void analyzeInit() {
		
		count = new int[] {0, 0};
		state = new int[] {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
		buffer = new byte[64];
	}

	public void analyzeUpdate(byte[] input, int inputLen) {
		
		analyzeUpdate(input, 0, inputLen);
	}
	
	public void analyzeUpdate(byte[] input, int ofs, int inputLen) {
		
		/* Compute number of bytes mod 64 */
		int i = 0, index = ((count[0] >> 3) & 0x3F);
		/* Update number of bits */
		count[0] += inputLen << 3;
		if (count[0] < (inputLen << 3)) {
		    count[1]++;
		}
		count[1] += (inputLen >> 29);

		int partLen = 64 - index;

		/* Transform as many times as possible.*/
		if (partLen <= inputLen) {
		    System.arraycopy(input, ofs, buffer, index, partLen);
		    md4Transform(state, buffer, 0);

		    for (i = partLen; i + 63 < inputLen; i += 64) {
		      md4Transform (state, input, i+ofs);
		    }

		    index = 0;
		  } else {
		    i = 0;
		  }

		  /* Buffer remaining input */
		  System.arraycopy(input, i+ofs, buffer, index, inputLen-i);
	}

	public byte[] analyzeFinal() {
		
		byte[] bits = new byte[8];

		/* Save number of bits */
		encode(bits, count, 8);

		/* Pad out to 56 mod 64. */
		int index = ((count[0] >> 3) & 0x3f);
		int padLen = (index < 56) ? (56 - index) : (120 - index);
		analyzeUpdate(PADDING, padLen);

		/* Append length (before padding) */
		analyzeUpdate(bits, 8);
		/* Store state in digest */
		byte[] digest = new byte[16];
		encode(digest, state, 16);
		
		return digest;
    }
	
}
