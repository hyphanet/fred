package freenet.crypt;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.Random;

import freenet.Core;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Implements the DLES asymmetric encryption algorithm, which 
 * while computatinally equivalent to ElGamal, provides much stronger
 * security.  In addition, encryption precomputation can be done in
 * advance.
 *
 * The scheme is described in DHAES: An Encryption Scheme Based on the Diffie
 * Hellman Problem, by Abdalla, Bellare, and Rogaway
 */
public class DLES {
    protected Digest H;
    protected HMAC MAC;
    protected BlockCipher sym;
    protected PCFBMode ctx;
    
    protected byte[] allZeros;

    public DLES() {
	this(new SHA256(), new Rijndael());
    }

    public DLES(Digest d, BlockCipher c) {
	H=d;
	MAC=new HMAC(SHA1.getInstance());
	sym=c;
	ctx=new PCFBMode(sym);
	allZeros=new byte[c.getBlockSize()>>3];
    }

    protected byte[] hash(BigInteger b1, BigInteger b2) {
	byte[] bb1=Util.MPIbytes(b1);
	byte[] bb2=Util.MPIbytes(b2);
	H.update(bb1);
	H.update(bb2);
	return H.digest();
    }

    protected byte[][] deriveKeys(byte[] hv) {
	byte[] encKey=new byte[sym.getKeySize() >> 3]; 
	System.arraycopy(hv, 0, encKey, 0, encKey.length);
	
	byte[] macKey=new byte[16]; //We'll use a 128 bit mac key
	System.arraycopy(hv, encKey.length, macKey, 0, macKey.length);

	return new byte[][] {encKey, macKey};
    }

    protected static byte[] bytes(BigInteger c) {
	byte[] b=Util.MPIbytes(c);
	int off=(b[2]==0 ? 3 : 2);
	byte[] rv=new byte[b.length-off];
	System.arraycopy(b,off,rv,0,b.length-off);
	return rv;
    }

    public BigInteger[] encrypt(DSAPublicKey pub, BigInteger M, 
				Random r) {
	return encrypt(pub, bytes(M), r);
    }

    public BigInteger[] encrypt(DSAPublicKey pub, byte[] M,
				Random r) {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	DSAGroup gr=pub.getGroup();
	BigInteger [] C=new BigInteger[3];

	// REDFLAG: this doesn't need to be NativeBigInt, right?
	BigInteger u=new BigInteger(pub.getY().bitLength(), r);
	
	long gotStuffTime = System.currentTimeMillis();
	
	BigInteger gu=gr.getG().modPow(u, gr.getP());
	BigInteger guv=pub.getY().modPow(u, gr.getP());
	
	C[0]=gu;

	byte[] hv=hash(gu, guv);
	
	byte[][] t=deriveKeys(hv);
	
	byte[] encKey=t[0];
	byte[] macKey=t[1];

	byte[] encM=new byte[M.length];
	System.arraycopy(M, 0, encM, 0, M.length);

	long encipheredTime;
	
	synchronized(sym) {
	    sym.initialize(encKey);
	    ctx.reset(allZeros);
	    encM=ctx.blockEncipher(encM, 0, encM.length);
	}

	// These don't need to be NativeBigInteger's either
	C[2]=new BigInteger(1, encM);
	
	byte[] macRes=MAC.mac(macKey, encM, H.digestSize()>>3);
	
	C[1]=new BigInteger(1, macRes);
	
	return C;
    }
    
    public BigInteger decrypt(DSAGroup gr, DSAPrivateKey p, BigInteger[] C) throws DecryptionFailedException {
	BigInteger X=C[0].modPow(p.getX(), gr.getP());
	byte[] hash=hash(C[0], X);
	
	byte[][] t=deriveKeys(hash);

	byte[] encKey=t[0];
	byte[] macKey=t[1];
	byte[] encM=bytes(C[2]);
	byte[] m=bytes(C[1]);

	if (MAC.verify(macKey, encM, bytes(C[1]))) {
	    synchronized (sym) {
		sym.initialize(encKey);
		ctx.reset(allZeros);
		// Does not need to be NativeBigInteger
		return new BigInteger(1,ctx.blockDecipher(encM, 0, encM.length));
	    }
	} else throw new DecryptionFailedException("MAC verify failed");
    }

    public static void main(String[] args) throws Exception {
	Yarrow y=new Yarrow();
	DLES dl=new DLES();
	DataInputStream dis=new DataInputStream(new FileInputStream(args[1]));
	if (args[0].equals("encrypt")) {
	    DSAPublicKey kp=new DSAPublicKey(Global.DSAgroupA,
					     (DSAPrivateKey)CryptoKey.read(dis));
	    byte[] M=HexUtil.hexToBytes(args[2]);
	    BigInteger[] C=null;
	    long start=System.currentTimeMillis();

	    //	    for (int i=0; i<1000; i++) {
		C=dl.encrypt(kp, M, y);
		//	    }
	    long end=System.currentTimeMillis();

	    for (int i=0; i<C.length-1; i++) {
		System.err.print(HexUtil.biToHex(C[i])+",");
		Util.writeMPI(C[i], System.out);
	    }
	    System.err.println(HexUtil.biToHex(C[C.length-1]));
	    Util.writeMPI(C[C.length-1], System.out);
	    System.err.println(end-start);
	} else if (args[0].equals("decrypt")) {
	    DSAPrivateKey kp=(DSAPrivateKey)CryptoKey.read(dis);
	    BufferedReader rd=new BufferedReader(new InputStreamReader(System.in));
	    BigInteger[] C=new BigInteger[]
	    { Util.readMPI(System.in),
	      Util.readMPI(System.in),
	      Util.readMPI(System.in) };
	    BigInteger M=null;

	    long start=System.currentTimeMillis();
	    //	    for (int i=0; i<1000; i++) {
		M=dl.decrypt(Global.DSAgroupA, kp, C);
		//	    }
	    long end=System.currentTimeMillis();
	    System.err.println("'"+HexUtil.biToHex(M)+"'");
	    System.err.println(end-start);
	}
    }
}



