/*
  DSASignature.java / Freenet
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;

import freenet.support.HexUtil;

import net.i2p.util.NativeBigInteger;


public class DSASignature implements CryptoElement, java.io.Serializable {
	private static final long serialVersionUID = -1;
    private final BigInteger r, s;
    private String toStringCached; //toString() cache 

    public DSASignature(InputStream in) throws IOException {
		r=Util.readMPI(in);
		s=Util.readMPI(in);
    }

    /**
     * Parses a DSA Signature pair from a string, where r and s are 
     * in unsigned hex-strings, separated by a comma
     */
    public DSASignature(String sig) throws NumberFormatException {
		int x=sig.indexOf(',');
		if (x <= 0)
	    	throw new NumberFormatException("DSA Signatures have two values");
		r = new NativeBigInteger(sig.substring(0,x), 16);
		s = new NativeBigInteger(sig.substring(x+1), 16);
    }

    public static DSASignature read(InputStream in) throws IOException {
		BigInteger r, s;
		r=Util.readMPI(in);
		s=Util.readMPI(in);
		return new DSASignature(r,s);
    }

    public void write(OutputStream o) throws IOException {
		Util.writeMPI(r, o);
		Util.writeMPI(s, o);
    }

    /** @deprecated
      * @see #toString()
      */
    public String writeAsField() {
        return toString();
    }
    
    public DSASignature(BigInteger r, BigInteger s) {
		this.r=r;
		this.s=s;
		if((r == null) || (s == null)) //Do not allow this sice we wont do any sanity checking beyond this place
			throw new NullPointerException();
    }

    public BigInteger getR() {
		return r;
    }

    public BigInteger getS() {
		return s;
    }

    public String toString() {
		if(toStringCached == null)
			toStringCached = HexUtil.biToHex(r) + "," + HexUtil.biToHex(s);
        return toStringCached;
    }
		  
}
