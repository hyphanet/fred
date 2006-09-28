/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.net.MalformedURLException;
import java.security.MessageDigest;

import net.i2p.util.NativeBigInteger;

import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA256;
import freenet.support.Logger;

/**
 * An insertable USK.
 * 
 * Changes from an ordinary USK:
 * - It has a private key
 * - getURI() doesn't include ,extra
 * - constructor from URI doesn't need or want ,extra
 * - It has a getUSK() method which gets the public USK
 */
public class InsertableUSK extends USK {
	
	public final DSAPrivateKey privKey;
	public final DSAGroup group;
	
	public static InsertableUSK createInsertable(FreenetURI uri) throws MalformedURLException {
		if(!uri.getKeyType().equalsIgnoreCase("USK"))
			throw new MalformedURLException();
		if((uri.getDocName() == null) || (uri.getDocName().length() == 0))
			throw new MalformedURLException("USK URIs must have a document name (to avoid ambiguity)");
		if(uri.getExtra() != null)
			throw new MalformedURLException("Insertable SSK URIs must NOT have ,extra - inserting from a pubkey rather than the privkey perhaps?");
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(new NativeBigInteger(1, uri.getKeyVal()));
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		MessageDigest md = SHA256.getMessageDigest();
		md.update(pubKey.asBytes());
		return new InsertableUSK(uri.getDocName(), md.digest(), uri.getCryptoKey(), privKey, g, uri.getSuggestedEdition());
	}
	
	InsertableUSK(String docName, byte[] pubKeyHash, byte[] cryptoKey, DSAPrivateKey key, DSAGroup group, long suggestedEdition) throws MalformedURLException {
		super(pubKeyHash, cryptoKey, docName, suggestedEdition);
		if(cryptoKey.length != ClientSSK.CRYPTO_KEY_LENGTH)
			throw new MalformedURLException("Decryption key wrong length: "+cryptoKey.length+" should be "+ClientSSK.CRYPTO_KEY_LENGTH);
		this.privKey = key;
		this.group = group;
	}
	
	public FreenetURI getURI() {
		return new FreenetURI(pubKeyHash, cryptoKey, null, siteName, suggestedEdition);
	}

	public USK getUSK() {
		return new USK(pubKeyHash, cryptoKey, siteName, suggestedEdition);
	}

	public InsertableClientSSK getInsertableSSK(long ver) {
		try {
			return new InsertableClientSSK(siteName + SEPARATOR + ver, pubKeyHash, 
					new DSAPublicKey(group, privKey), privKey, cryptoKey);
		} catch (MalformedURLException e) {
			Logger.error(this, "Caught "+e+" should not be possible in USK.getSSK", e);
			throw new Error(e);
		}
	}

	public InsertableUSK privCopy(long edition) {
		if(edition == suggestedEdition) return this;
		try {
			return new InsertableUSK(siteName, pubKeyHash, cryptoKey, privKey, group, edition);
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}


}
