/*
  HMAC.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.crypt;

import java.util.Arrays;

import freenet.support.HexUtil;

/**
 * Implements the HMAC Keyed Message Authentication function, as described
 * in the draft FIPS standard.
 */
public class HMAC {
    protected static final int B=64;
    protected static byte[] ipad=new byte[B];
    protected static byte[] opad=new byte[B];

    static {
	for (int i=0; i<B; i++) {
	    ipad[i]=(byte)0x36;
	    opad[i]=(byte)0x5c;
	}
    }

    protected Digest d;

    public HMAC(Digest d) {
	this.d=d;
    }

    public int digestSize() {
	return d.digestSize();
    }
    
    public Digest getDigest() {
	return d;
    }

    public boolean verify(byte[] K, byte[] text, byte[] mac) {
	byte[] mac2=mac(K, text, mac.length);
	return Arrays.equals(mac, mac2);
    }

    public byte[] mac(byte[] K, byte[] text, int macbytes) {
	byte[] K0=null;

	if (K.length==B) // Step 1
	    K0=K;
	else {
	    // Step 2
	    if (K.length > B) { 
		K=Util.hashBytes(d, K);
	    }
	    
	    if (K.length < B) { // Step 3
		K0=new byte[B];
		System.arraycopy(K, 0, K0, 0, K.length);
	    }  
	}

	// Step 4
	byte[] IS1=Util.xor(K0, ipad);
	
	// Step 5/6
	d.update(IS1);
	d.update(text);
	IS1=d.digest();
	
	// Step 7
	byte[] IS2=Util.xor(K0, opad);

	// Step 8/9
	d.update(IS2);
	d.update(IS1);
	IS1=d.digest();
	
	// Step 10
	if (macbytes == IS1.length) 
	    return IS1;
	else {
	    byte[] rv=new byte[macbytes];
	    System.arraycopy(IS1, 0, rv, 0, Math.min(rv.length, IS1.length));
	    return rv;
	}
    }

    public static void main(String[] args) {
	HMAC s=new HMAC(SHA1.getInstance());
	byte[] key=new byte[20];
	System.err.println("20x0b, 'Hi There':");
	byte[] text="Hi There".getBytes();
	
	for (int i=0; i<key.length; i++) key[i]=(byte)0x0b;

	byte[] mv=s.mac(key, text, 20);
	System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

	System.err.println("20xaa, 50xdd:");
	for (int i=0; i<key.length; i++) key[i]=(byte)0xaa;
	text=new byte[50];
	for (int i=0; i<text.length; i++) text[i]=(byte)0xdd;
	mv=s.mac(key, text, 20);
	System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

	key=new byte[25];
	System.err.println("25x[i+1], 50xcd:");
	for (int i=0; i<key.length; i++) key[i]=(byte)(i+1);
	for (int i=0; i<text.length; i++) text[i]=(byte)0xcd;
	mv=s.mac(key, text, 20);
	System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

	key=new byte[20];
	System.err.println("20x0c, 'Test With Truncation':");
	for (int i=0; i<key.length; i++) key[i]=(byte)0x0c;
	text="Test With Truncation".getBytes();
	mv=s.mac(key, text, 20);
	System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));
	mv=s.mac(key, text, 12);
	System.out.println(HexUtil.bytesToHex(mv, 0, mv.length));

    }

}	
