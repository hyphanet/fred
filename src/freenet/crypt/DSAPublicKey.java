/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.crypt;

import java.math.BigInteger;
import java.io.*;

import freenet.support.HexUtil;

import net.i2p.util.NativeBigInteger;

public class DSAPublicKey extends CryptoKey {
    
    private final BigInteger y;
	/** A cache of the hexadecimal string representation of y */
    private final String yAsHexString; 

    private final DSAGroup group;
	
	private byte[] fingerprint = null;
    
    public DSAPublicKey(DSAGroup g, BigInteger y) {
		this.y=y;
		this.yAsHexString = HexUtil.biToHex(y);
		this.group=g;
    }
	
    /**
	 * Use this constructor if you have a Hex:ed version of y already
	 * available, will save some conversions and string allocations.
	 */
	public DSAPublicKey(DSAGroup g, String yAsHexString) throws NumberFormatException {
		this.y=new NativeBigInteger(yAsHexString,16);
		this.yAsHexString = yAsHexString;
		this.group=g;
	}

    public DSAPublicKey(DSAGroup g, DSAPrivateKey p) {
		this(g,g.getG().modPow(p.getX(), g.getP()));
    }

    public BigInteger getY() {
		return y;
    }
    
	public String getYAsHexString() {
		return yAsHexString;
	}

    public BigInteger getP() {
		return group.getP();
    }
    
	public String getPAsHexString() {
		return group.getPAsHexString();
	}
    
    public BigInteger getQ() {
		return group.getQ();
    }
    
	public String getQAsHexString() {
		return group.getQAsHexString();
	}

    public BigInteger getG() {
		return group.getG();
    }
    
	public String getGAsHexString() {
		return group.getGAsHexString();
	}

    public String keyType() {
		return "DSA.p";
    }

    // Nope, this is fine
    public DSAGroup getGroup() {
		return group;
    }

    public void write(OutputStream out) throws IOException {
		writeWithoutGroup(out);
		group.write(out);
    }

    public void writeForWireWithoutGroup(OutputStream out) throws IOException {
		Util.writeMPI(y, out);
    }

    public void writeForWire(OutputStream out) throws IOException {
		Util.writeMPI(y, out);
		group.writeForWire(out);
    }

    public void writeWithoutGroup(OutputStream out) 
	throws IOException {
		write(out, getClass().getName());
		Util.writeMPI(y, out);
    }

    public static CryptoKey read(InputStream i) throws IOException {
		BigInteger y=Util.readMPI(i);
		DSAGroup g=(DSAGroup)CryptoKey.read(i);
		return new DSAPublicKey(g, y);
    }

    public int keyId() {
		return y.intValue();
    }

    public String writeAsField() {
        return yAsHexString;
    }

    // this won't correctly read the output from writeAsField
    //public static CryptoKey readFromField(DSAGroup group, String field) {
    //    BigInteger y=Util.byteArrayToMPI(Util.hexToBytes(field));
    //    return new DSAPublicKey(group, y);
    //}

    public byte[] asBytes() {
		byte[] groupBytes=group.asBytes();
		byte[] ybytes=Util.MPIbytes(y);
		byte[] bytes=new byte[groupBytes.length + ybytes.length];
		System.arraycopy(groupBytes, 0, bytes, 0, groupBytes.length);
		System.arraycopy(ybytes, 0, bytes, groupBytes.length, ybytes.length);
		return bytes;
    }

    public byte[] fingerprint() {
		synchronized(this) {
			if(fingerprint == null)
				fingerprint = fingerprint(new BigInteger[] {y});
			return fingerprint;
		}
    }
	
    public boolean equals(DSAPublicKey o) {
    	if(this == o) // Not necessary, but a very cheap optimization
    		return true;
		return y.equals(o.y) && group.equals(o.group);
    }

    public boolean equals(Object o) {
    	if(this == o) // Not necessary, but a very cheap optimization
    		return true;
		return (o instanceof DSAPublicKey)
		    && y.equals(((DSAPublicKey) o).y)
		    && group.equals(((DSAPublicKey) o).group);
    }
    
    public int compareTo(Object other) {
		if (other instanceof DSAPublicKey) {
	    	return getY().compareTo(((DSAPublicKey)other).getY());
		} else return -1;
    }
}
