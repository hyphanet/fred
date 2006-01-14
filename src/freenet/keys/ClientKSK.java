package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;

public class ClientKSK extends InsertableClientSSK {

	final String keyword;
	
	private ClientKSK(String keyword, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] keywordHash) throws MalformedURLException {
		super(keyword, pubKeyHash, pubKey, privKey, keywordHash);
		this.keyword = keyword;
	}

	public FreenetURI getURI() {
		return new FreenetURI("KSK", keyword);
	}
	
	public static InsertableClientSSK create(FreenetURI uri) {
		if(!uri.getKeyType().equals("KSK"))
			throw new IllegalArgumentException();
		return create(uri.getDocName());
	}
	
	public static ClientKSK create(String keyword) {
		MessageDigest md256;
		try {
			md256 = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		byte[] keywordHash;
		try {
			keywordHash = md256.digest(keyword.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		MersenneTwister mt = new MersenneTwister(keywordHash);
		DSAPrivateKey privKey = new DSAPrivateKey(Global.DSAgroupBigA, mt);
		DSAPublicKey pubKey = new DSAPublicKey(Global.DSAgroupBigA, privKey);
		byte[] pubKeyHash = md256.digest(pubKey.asBytes());
		try {
			return new ClientKSK(keyword, pubKeyHash, pubKey, privKey, keywordHash);
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}
	
}
