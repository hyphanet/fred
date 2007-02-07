/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;

import org.spaceroots.mantissa.random.MersenneTwister;

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
			SHA256.returnMessageDigest(md256);
			return new ClientKSK(keyword, pubKeyHash, pubKey, privKey, keywordHash);
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}
	
}
