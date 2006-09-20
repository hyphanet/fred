/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.crypt;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.io.*;

import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

import net.i2p.util.NativeBigInteger;

public class DSAPublicKey extends CryptoKey {
	private static final long serialVersionUID = -1;
    
    private final BigInteger y;
	/** A cache of the hexadecimal string representation of y */
    private final String yAsHexString; 
    
    public static final int PADDED_SIZE = 1024;

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

    public DSAPublicKey(InputStream is) throws IOException {
		group=(DSAGroup) DSAGroup.read(is);
		y=Util.readMPI(is);
		this.yAsHexString = HexUtil.biToHex(y);
    }
    
    public DSAPublicKey(byte[] pubkeyAsBytes) throws IOException {
    	this(new ByteArrayInputStream(pubkeyAsBytes));
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

//    public void writeForWireWithoutGroup(OutputStream out) throws IOException {
//		Util.writeMPI(y, out);
//    }
//
//    public void writeForWire(OutputStream out) throws IOException {
//		Util.writeMPI(y, out);
//		group.writeForWire(out);
//    }
//
//    public void writeWithoutGroup(OutputStream out) 
//	throws IOException {
//		write(out, getClass().getName());
//		Util.writeMPI(y, out);
//    }
//
    public static CryptoKey read(InputStream i) throws IOException {
		return new DSAPublicKey(i);
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

    public byte[] asBytesHash() {
    	MessageDigest md256 = SHA256.getMessageDigest();
    	return md256.digest(asBytes());
    }
    
    public byte[] asPaddedBytes() {
    	byte[] asBytes = asBytes();
    	if(asBytes.length == PADDED_SIZE)
    		return asBytes;
    	if(asBytes.length > PADDED_SIZE)
    		throw new Error("Cannot fit key in "+PADDED_SIZE+" - real size is "+asBytes.length);
    	byte[] padded = new byte[PADDED_SIZE];
    	System.arraycopy(asBytes, 0, padded, 0, asBytes.length);
    	return padded;
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

	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("y", Base64.encode(y.toByteArray()));
		return fs;
	}

	public static DSAPublicKey create(SimpleFieldSet set, DSAGroup group) throws IllegalBase64Exception {
		NativeBigInteger x = 
			new NativeBigInteger(1, Base64.decode(set.get("y")));
		return new DSAPublicKey(group, x);
	}
}
