package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Describes a general key pair system with support for multiple-value
 * keys.
 */
public class KeyPair {
    protected static final Class[] KEYPAIR_CONSTRUCTOR=
	new Class[] {CryptoKey.class, CryptoKey.class};

    protected final CryptoKey publicKey, privateKey;

    public KeyPair(CryptoKey pubkey, CryptoKey privkey) {
	publicKey=pubkey;
	privateKey=privkey;
    }

    public static KeyPair read(InputStream i) throws IOException {
	CryptoKey pubk=CryptoKey.read(i);
	CryptoKey privk=CryptoKey.read(i);
	/*		DataInputStream in=new DataInputStream(i);
	
	String className=in.readUTF();

	try {
	    KeyPair kp=(KeyPair)Loader.getInstance(className, KEYPAIR_CONSTRUCTOR, 
						   new Object[] {pubk, privk});
	    return kp;
	} catch (Exception e) {
	    return null;
	    }*/
	return new KeyPair(pubk, privk);
    }

    protected void write(OutputStream o) throws IOException {
	publicKey.write(o);
	privateKey.write(o);
    }

    public CryptoKey getPublicKey() {
	return publicKey;
    }

    public CryptoKey getPrivateKey() {
	return privateKey;
    }

    public byte[] fingerprint() {
	return publicKey.fingerprint();
    }

    public static void main(String[] args) throws IOException {
	for (;;) {
	    KeyPair kp=KeyPair.read(System.in);
	    System.err.println("-+ " + kp.getPrivateKey().verboseToString());
	    System.err.println(" + " + kp.getPublicKey().verboseToString());
	}
    }
}	


