package freenet.crypt;

import java.math.BigInteger;
import java.io.*;
import java.util.Random;

import freenet.support.HexUtil;

import net.i2p.util.NativeBigInteger;

public class DSAPrivateKey extends CryptoKey {

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
        BigInteger x;
        do {
            x = new NativeBigInteger(160, r);
        } while (x.compareTo(g.getQ()) > -1);
        this.x = x;
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
    
    public void write(OutputStream out) throws IOException {
        write(out, getClass().getName());
        Util.writeMPI(x, out);
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
    
    public static void main(String[] args) throws Exception {
        Yarrow y=new Yarrow();
        DSAPrivateKey p=new DSAPrivateKey(Global.DSAgroupC, y);
        DSAPublicKey pk=new DSAPublicKey(Global.DSAgroupC, p);
        p.write(System.out);
    }
}

