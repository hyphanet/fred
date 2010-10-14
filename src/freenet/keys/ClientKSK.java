/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/** A KSK. We know the private key from the keyword, so this can be both 
 * requested and inserted. */
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;

import freenet.support.math.MersenneTwister;

import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA256;

public class ClientKSK extends InsertableClientSSK {

	final String keyword;
	
	private ClientKSK(String keyword, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] keywordHash) throws MalformedURLException {
		super(keyword, pubKeyHash, pubKey, privKey, keywordHash, Key.ALGO_AES_PCFB_256_SHA256);
		this.keyword = keyword;
	}

	@Override
	public FreenetURI getURI() {
		return new FreenetURI("KSK", keyword);
	}
	
	public static InsertableClientSSK create(FreenetURI uri) {
		if(!uri.getKeyType().equals("KSK"))
			throw new IllegalArgumentException();
		return create(uri.getDocName());
	}
	
	public static ClientKSK create(String keyword) {
		MessageDigest md256 = SHA256.getMessageDigest();
		try {
			byte[] keywordHash;
			try {
				keywordHash = md256.digest(keyword.getBytes("UTF-8"));
			} catch (UnsupportedEncodingException e) {
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
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
		} finally {
			SHA256.returnMessageDigest(md256);
		}
	}
	
}
