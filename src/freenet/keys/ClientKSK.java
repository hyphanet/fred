/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/** A KSK. We know the private key from the keyword, so this can be both 
 * requested and inserted. */

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA256;
import freenet.support.math.MersenneTwister;

public class ClientKSK extends InsertableClientSSK {

    private static final long serialVersionUID = 1L;
    final String keyword;
	
	private ClientKSK(String keyword, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] keywordHash) throws MalformedURLException {
		super(keyword, pubKeyHash, pubKey, privKey, keywordHash, Key.ALGO_AES_PCFB_256_SHA256);
		this.keyword = keyword;
	}
	
	protected ClientKSK() {
	    // For serialization.
	    keyword = null;
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
		byte[] keywordHash = md256.digest(keyword.getBytes(StandardCharsets.UTF_8));
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
