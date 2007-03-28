/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
		if(r.signum() != 1 || s.signum() != 1) throw new IllegalArgumentException();
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

    public DSASignature(BigInteger r, BigInteger s) {
		this.r=r;
		this.s=s;
		if((r == null) || (s == null)) //Do not allow this sice we wont do any sanity checking beyond this place
			throw new NullPointerException();
		if(r.signum() != 1 || s.signum() != 1) throw new IllegalArgumentException();
    }

    public BigInteger getR() {
		return r;
    }

    public BigInteger getS() {
		return s;
    }

    public String toLongString() {
		if(toStringCached == null)
			toStringCached = HexUtil.biToHex(r) + ',' + HexUtil.biToHex(s);
        return toStringCached;
    }

	public byte[] getRBytes(int length) {
		return getParamBytes(r, length);
	}

	public byte[] getSBytes(int length) {
		return getParamBytes(s, length);
	}

	private static byte[] getParamBytes(BigInteger param, int length) {
		byte[] data = param.toByteArray();
		if(data.length < length) {
			byte[] out = new byte[length];
			System.arraycopy(data, 0, out, out.length - data.length, data.length);
			return out;
		} else if(data.length == length+1) {
			if(data[0] == 0) {
				byte[] out = new byte[length];
				System.arraycopy(data, 1, out, 0, length);
				return out;
			} else
				throw new IllegalArgumentException("Parameter longer than "+length+" bytes : "+param.bitLength());
		} else if(data.length == length) {
			return data;
		} else throw new IllegalArgumentException("Length is much shorter: "+data.length+" but target length = "+length);
	}
		  
}
