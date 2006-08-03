package freenet.crypt;

import java.math.BigInteger;
import java.io.*;
import java.util.Random;

import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

import net.i2p.util.NativeBigInteger;

public class DSAPrivateKey extends CryptoKey {
	private static final long serialVersionUID = -1;

    private final BigInteger x;

    public DSAPrivateKey(BigInteger x) {
        this.x = x;
    }

    // this is dangerous...  better to force people to construct the
    // BigInteger themselves so they know what is going on with the sign
    //public DSAPrivateKey(byte[] x) {
    //    this.x = new BigInteger(1, x);
    //}

    public DSAPrivateKey(DSAGroup g, Random r) {
        BigInteger tempX;
        do {
            tempX = new NativeBigInteger(256, r);
        } while (tempX.compareTo(g.getQ()) > -1);
        this.x = tempX;
    }

    public String keyType() {
        return "DSA.s";
    }
    
    public BigInteger getX() {
        return x;
    }
    
    public static CryptoKey read(InputStream i) throws IOException {
        return new DSAPrivateKey(Util.readMPI(i));
    }
    
    public String writeAsField() {
        return HexUtil.biToHex(x);
    }
    
    // what?  why is DSAGroup passed in?
    //public static CryptoKey readFromField(DSAGroup group, String field) {
    //    //BigInteger x=Util.byteArrayToMPI(Util.hexToBytes(field));
    //    return new DSAPrivateKey(new BigInteger(field, 16));
    //}
    
    public byte[] asBytes() {
        return Util.MPIbytes(x);
    }
    
    public byte[] fingerprint() {
        return fingerprint(new BigInteger[] {x});
    }

	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("x", Base64.encode(x.toByteArray()));
		return fs;
	}

	public static DSAPrivateKey create(SimpleFieldSet fs, DSAGroup group, boolean base64) throws IllegalBase64Exception {
		NativeBigInteger y = new NativeBigInteger(1, 
				base64 ? Base64.decode(fs.get("x")) :
				HexUtil.hexToBytes(fs.get("x")));
		if(y.bitLength() > 512)
			throw new IllegalBase64Exception("Probably a pubkey");
		return new DSAPrivateKey(y);
	}
    
//    public static void main(String[] args) throws Exception {
//        Yarrow y=new Yarrow();
//        DSAPrivateKey p=new DSAPrivateKey(Global.DSAgroupC, y);
//        DSAPublicKey pk=new DSAPublicKey(Global.DSAgroupC, p);
//        p.write(System.out);
//    }
}

