/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

import com.db4o.ObjectContainer;

import freenet.support.Fields;
import freenet.support.Logger;

/**
 * Updatable Subspace Key.
 * Not really a ClientKey as it cannot be directly requested.
 * 
 * Contains:
 * - Enough information to produce a real SSK.
 * - Site name.
 * - Site edition number.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class USK extends BaseClientKey implements Comparable<USK> {

	/* The character to separate the site name from the edition number in its SSK form.
	 * I chose "-", because it makes it ludicrously easy to go from the USK form to the
	 * SSK form, and we don't need to go vice versa.
	 */
	static protected final String SEPARATOR = "-";
	/** Encryption type */
	public final byte cryptoAlgorithm;
	/** Public key hash */
	protected final byte[] pubKeyHash;
	/** Encryption key */
	protected final byte[] cryptoKey;
	// Extra must be verified on creation, and is fixed for now. FIXME if it becomes changeable, need to keep values here.
	
	public final String siteName;
	public final long suggestedEdition;
	
	private final int hashCode;

	public USK(byte[] pubKeyHash, byte[] cryptoKey, byte[] extra, String siteName, long suggestedEdition) throws MalformedURLException {
		this.pubKeyHash = pubKeyHash;
		this.cryptoKey = cryptoKey;
		this.siteName = siteName;
		this.suggestedEdition = suggestedEdition;
		if(extra == null)
			throw new MalformedURLException("No extra bytes (third bit) in USK");
		if(pubKeyHash == null)
			throw new MalformedURLException("No pubkey hash (first bit) in USK");
		if(cryptoKey == null)
			throw new MalformedURLException("No crypto key (second bit) in USK");
		// Verify extra bytes, get cryptoAlgorithm - FIXME this should be a static method or something?
		ClientSSK tmp = new ClientSSK(siteName, pubKeyHash, extra, null, cryptoKey);
		cryptoAlgorithm = tmp.cryptoAlgorithm;
		if(pubKeyHash.length != NodeSSK.PUBKEY_HASH_SIZE)
			throw new MalformedURLException("Pubkey hash wrong length: "+pubKeyHash.length+" should be "+NodeSSK.PUBKEY_HASH_SIZE);
		if(cryptoKey.length != ClientSSK.CRYPTO_KEY_LENGTH)
			throw new MalformedURLException("Decryption key wrong length: "+cryptoKey.length+" should be "+ClientSSK.CRYPTO_KEY_LENGTH);
		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^
			siteName.hashCode() ^ (int)suggestedEdition ^ (int)(suggestedEdition >> 32);
	}

	public static USK create(FreenetURI uri) throws MalformedURLException {
		if(!uri.isUSK()) throw new MalformedURLException("Not a USK");
		return new USK(uri.getRoutingKey(), uri.getCryptoKey(), uri.getExtra(), uri.getDocName(), uri.getSuggestedEdition());
	}
	
	protected USK(byte[] pubKeyHash2, byte[] cryptoKey2, String siteName2, long suggestedEdition2, byte cryptoAlgorithm) {
		this.pubKeyHash = pubKeyHash2;
		this.cryptoKey = cryptoKey2;
		this.siteName = siteName2;
		this.suggestedEdition = suggestedEdition2;
		this.cryptoAlgorithm = cryptoAlgorithm;
		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^
			siteName.hashCode() ^ (int)suggestedEdition ^ (int)(suggestedEdition >> 32);
	}

	private static final Pattern badDocNamePattern;
	static {
		badDocNamePattern = Pattern.compile(".*\\-[0-9]+(\\/.*)?$");
	}

	// FIXME: Be careful with this constructor! There must not be an edition in the ClientSSK!
	public USK(ClientSSK ssk, long myARKNumber) {
		this.pubKeyHash = ssk.pubKeyHash;
		this.cryptoKey = ssk.cryptoKey;
		this.siteName = ssk.docName;
		this.suggestedEdition = myARKNumber;
		this.cryptoAlgorithm = ssk.cryptoAlgorithm;

		if (badDocNamePattern.matcher(siteName).matches())	// not error -- just "possible" bug
			Logger.normal(this, "POSSIBLE BUG: edition in ClientSSK " + ssk, new Exception("debug"));

		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^
			siteName.hashCode() ^ (int)suggestedEdition ^ (int)(suggestedEdition >> 32);
	}

	public USK(USK usk) {
		// FIXME can we not copy pubKeyHash?
		// If we can guarantee that neither USK nor anything getting it without copying will change it?
		// db4o treats byte[] as individual byte members, so there are no issues with deactivation.
		this.pubKeyHash = usk.pubKeyHash.clone();
		this.cryptoAlgorithm = usk.cryptoAlgorithm;
		// FIXME should we copy cryptoKey?
		this.cryptoKey = usk.cryptoKey;
		this.siteName = usk.siteName;
		this.suggestedEdition = usk.suggestedEdition;
		hashCode = Fields.hashCode(pubKeyHash) ^ Fields.hashCode(cryptoKey) ^
			siteName.hashCode() ^ (int)suggestedEdition ^ (int)(suggestedEdition >> 32);
	}

	@Override
	public FreenetURI getURI() {
		return new FreenetURI(pubKeyHash, cryptoKey, ClientSSK.getExtraBytes(cryptoAlgorithm), siteName, suggestedEdition);
	}

	public ClientSSK getSSK(long ver) {
		return getSSK(getName(ver));
	}
	
	public ClientSSK getSSK(String string) {
		try {
			return new ClientSSK(string, pubKeyHash, ClientSSK.getExtraBytes(cryptoAlgorithm), null, cryptoKey);
		} catch (MalformedURLException e) {
			Logger.error(this, "Caught "+e+" should not be possible in USK.getSSK", e);
			throw new Error(e);
		}
	}
	
	public String getName(long ver) {
		return siteName + SEPARATOR + ver;
	}

	public ClientKey getSSK() {
		return getSSK(suggestedEdition);
	}
	
	public USK copy(long edition) {
		if(suggestedEdition == edition) return this;
		return new USK(pubKeyHash, cryptoKey, siteName, edition, cryptoAlgorithm);
	}

	public USK clearCopy() {
		return copy(0);
	}
	
	public final USK copy() {
		// We need our own constructor to make sure we copy pubKeyHash.
		// So clone() doesn't work for this.
		// FIXME when we are sure we don't need to copy the byte[]'s we might be able to switch back to clone().
		return new USK(this);
	}
	
	@Override
	public boolean equals(Object o) {
		if(o == null || !(o instanceof USK)) return false;
		return equals(o, true);
	}
	
	public boolean equals(Object o, boolean includeVersion) {
		if(o instanceof USK) {
			USK u = (USK)o;
			if(!Arrays.equals(pubKeyHash, u.pubKeyHash)) return false;
			if(!Arrays.equals(cryptoKey, u.cryptoKey)) return false;
			if(!siteName.equals(u.siteName)) return false;
			if(includeVersion && (suggestedEdition != u.suggestedEdition)) return false;
			return true;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	public FreenetURI getBaseSSK() {
		return new FreenetURI("SSK", siteName, pubKeyHash, cryptoKey, ClientSSK.getExtraBytes(cryptoAlgorithm));
	}
	
	@Override
	public String toString() {
		return super.toString()+ ':' +getURI();
	}

	public FreenetURI turnMySSKIntoUSK(FreenetURI uri) {
		if(uri.getKeyType().equals("SSK") &&
				Arrays.equals(uri.getRoutingKey(), pubKeyHash) &&
				Arrays.equals(uri.getCryptoKey(), cryptoKey) &&
				Arrays.equals(uri.getExtra(), ClientSSK.getExtraBytes(cryptoAlgorithm)) &&
				uri.getDocName() != null &&
				uri.getDocName().startsWith(siteName)) {
			String doc = uri.getDocName();
			doc = doc.substring(siteName.length());
			if(doc.length() < 2 || doc.charAt(0) != '-') return uri;
			doc = doc.substring(1);
			long edition;
			try {
				edition = Long.parseLong(doc);
			} catch (NumberFormatException e) {
				Logger.normal(this, "Trying to turn SSK back into USK: "+uri+" doc="+doc+" caught "+e, e);
				return uri;
			}
			if(!doc.equals(Long.toString(edition))) return uri;
			return new FreenetURI("USK", siteName, uri.getAllMetaStrings(), pubKeyHash, cryptoKey, ClientSSK.getExtraBytes(cryptoAlgorithm), edition);
		}
		return uri;
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	@Override
	public int compareTo(USK o) {
		if(this == o) return 0;
		if(cryptoAlgorithm < o.cryptoAlgorithm) return -1;
		if(cryptoAlgorithm > o.cryptoAlgorithm) return 1;
		int cmp = Fields.compareBytes(pubKeyHash, o.pubKeyHash);
		if(cmp != 0) return cmp;
		cmp = Fields.compareBytes(cryptoKey, o.cryptoKey);
		if(cmp != 0) return cmp;
		cmp = siteName.compareTo(o.siteName);
		if(cmp != 0) return cmp;
		if(suggestedEdition > o.suggestedEdition) return 1;
		if(suggestedEdition < o.suggestedEdition) return -1;
		return 0;
	}
	
	public static final Comparator<USK> FAST_COMPARATOR = new Comparator<USK>() {

		@Override
		public int compare(USK o1, USK o2) {
			if(o1.hashCode > o2.hashCode) return 1;
			else if(o1.hashCode < o2.hashCode) return -1;
			return o1.compareTo(o2);
		}
		
	};

	public byte[] getPubKeyHash() {
		return Arrays.copyOf(pubKeyHash, pubKeyHash.length);
	}

	public boolean samePubKeyHash(NodeSSK k) {
		return Arrays.equals(k.getPubKeyHash(), pubKeyHash);
	}
}
