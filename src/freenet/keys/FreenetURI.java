package freenet.keys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.Vector;

import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;

/**
 * Note that the metadata pairs below are not presently supported. They are supported
 * by the old (0.5) code however.
 * 
 * FreenetURI handles parsing and creation of the Freenet URI format, defined
 * as follows:
 * <p>
 * <code>freenet:[KeyType@]RoutingKey,CryptoKey[,n1=v1,n2=v2,...][/docname][/metastring]</code>
 * </p>
 * <p>
 * where KeyType is the TLA of the key (currently SVK, SSK, KSK, or CHK). If
 * omitted, KeyType defaults to KSK.
 * BUT: CHKs don't support or require a docname.
 * KSKs and SSKs do.
 * Therefore CHKs go straight into metastrings.
 * </p>
 * <p>
 * For KSKs, the string keyword (docname) takes the RoutingKey position and the
 * remainder of the fields are inapplicable (except metastring). Examples:
 * <coe>freenet:KSK@foo/bar freenet:KSK@test.html freenet:test.html</code>.
 * </p>
 * <p>
 * RoutingKey is the modified Base64 encoded key value. CryptoKey is the
 * modified Base64 encoded decryption key.
 * </p>
 * <p>
 * Following the RoutingKey and CryptoKey there may be a series of <code>
 * name=value</code> pairs representing URI meta-information.
 * </p>
 * <p>
 * The docname is only meaningful for SSKs, and is hashed with the PK
 * fingerprint to get the key value.
 * </p>
 * <p>
 * The metastring is meant to be passed to the metadata processing systems that
 * act on the retrieved document.
 * </p>
 * 
 * REDFLAG: Old code has a FieldSet, and the ability to put arbitrary metadata
 * in through name/value pairs. Do we want this?
 */
public class FreenetURI {

	private String keyType, docName;
	private String[] metaStr;
	private byte[] routingKey, cryptoKey, extra;
	private long suggestedEdition; // for USKs

	public Object clone() {
		return new FreenetURI(this);
	}
	
	private FreenetURI(FreenetURI uri) {
		keyType = uri.keyType;
		docName = uri.docName;
		metaStr = new String[uri.metaStr.length];
		for(int i=0;i<metaStr.length;i++)
			metaStr[i] = uri.metaStr[i];
		if(uri.routingKey != null) {
			routingKey = new byte[uri.routingKey.length];
			System.arraycopy(uri.routingKey, 0, routingKey, 0, routingKey.length);
		}
		if(uri.cryptoKey != null) {
			cryptoKey = new byte[uri.cryptoKey.length];
			System.arraycopy(uri.cryptoKey, 0, cryptoKey, 0, cryptoKey.length);
		}
		if(uri.extra != null) {
			extra = new byte[uri.extra.length];
			System.arraycopy(uri.extra, 0, extra, 0, extra.length);
		}
	}
	
	public FreenetURI(String keyType, String docName) {
		this(keyType, docName, (String[]) null, null, null, null);
	}

	public static final FreenetURI EMPTY_CHK_URI = new FreenetURI("CHK", null, null, null, null, null);
	
	public FreenetURI(
		String keyType,
		String docName,
		byte[] routingKey,
		byte[] cryptoKey, byte[] extra2) {
		this(keyType, docName, (String[]) null, routingKey, cryptoKey, extra2);
	}

	public FreenetURI(
		String keyType,
		String docName,
		String metaStr,
		byte[] routingKey,
		byte[] cryptoKey) {
		this(
			keyType,
			docName,
			(metaStr == null ? (String[]) null : new String[] { metaStr }),
			routingKey,
			cryptoKey,
			null);

	}

	public FreenetURI(
		String keyType,
		String docName,
		String[] metaStr,
		byte[] routingKey,
		byte[] cryptoKey, byte[] extra2) {
		this.keyType = keyType.trim().toUpperCase();
		this.docName = docName;
		this.metaStr = metaStr;
		this.routingKey = routingKey;
		this.cryptoKey = cryptoKey;
		this.extra = extra2;
	}

	public FreenetURI(String URI) throws MalformedURLException {
		if (URI == null) {
			throw new MalformedURLException("No URI specified");
		}
		// check scheme
		int colon = URI.indexOf(':');
		if (colon != -1
			&& !URI.substring(0, colon).equalsIgnoreCase("freenet")) {
			throw new MalformedURLException("Invalid scheme for Freenet URI");
		}

		// decode keyType
		int atchar = URI.indexOf('@');
		if (atchar == -1) {
			throw new MalformedURLException();
		} else {
			keyType = URI.substring(colon + 1, atchar).toUpperCase().trim();
		}
		URI = URI.substring(atchar + 1);

		// decode metaString
		int slash2;
		Vector sv = new Vector();
		while ((slash2 = URI.lastIndexOf("/")) != -1) {
			String s = URI.substring(slash2 + "/".length());
			if (s != null)
				sv.addElement(s);
			URI = URI.substring(0, slash2);
		}
		boolean b = false;
		if("SSK".equals(keyType) || (b="USK".equals(keyType))) {
			// docName not necessary, nor is it supported, for CHKs.
			
			if(sv.isEmpty())
				throw new MalformedURLException("No docname");
			docName = (String) sv.remove(sv.size()-1);
			if(b) {
				if(sv.isEmpty()) throw new MalformedURLException("No suggested edition number for USK");
				try {
					suggestedEdition = Long.parseLong((String)sv.remove(sv.size()-1));
				} catch (NumberFormatException e) {
					MalformedURLException e1 = new MalformedURLException("Invalid suggested edition: "+e);
					e1.initCause(e);
					throw e1;
				}
			}
		}
		
		if (!sv.isEmpty()) {
			metaStr = new String[sv.size()];
			for (int i = 0; i < metaStr.length; i++)
				metaStr[i] = (String) sv.elementAt(metaStr.length - 1 - i);
		}

		if(keyType.equalsIgnoreCase("KSK")) {
			docName = URI;
			return;
		}
		
		// URI now contains: routingKey[,cryptoKey][,metaInfo]
		StringTokenizer st = new StringTokenizer(URI, ",");
		try {
			if (st.hasMoreTokens()) {
				routingKey = Base64.decode(st.nextToken());
			}
			if (!st.hasMoreTokens()) {
				return;
			}

			// Can be cryptokey or name-value pair.
			String t = st.nextToken();
			cryptoKey = Base64.decode(t);
			if (!st.hasMoreTokens()) {
				return;
			}
			extra = Base64.decode(st.nextToken());

		} catch (IllegalBase64Exception e) {
			throw new MalformedURLException("Invalid Base64 quantity: " + e);
		}
	}

	/** USK constructor from components. */
	public FreenetURI(byte[] pubKeyHash, byte[] cryptoKey2, String siteName, long suggestedEdition2) {
		this.keyType = "USK";
		this.routingKey = pubKeyHash;
		this.cryptoKey = cryptoKey2;
		this.docName = siteName;
		this.suggestedEdition = suggestedEdition2;
	}

	public void decompose() {
		String r = routingKey == null ? "none" : HexUtil.bytesToHex(routingKey);
		String k = cryptoKey == null ? "none" : HexUtil.bytesToHex(cryptoKey);
		String e = extra == null ? "none" : HexUtil.bytesToHex(extra);
		System.out.println("" + this);
		System.out.println("Key type   : " + keyType);
		System.out.println("Routing key: " + r);
		System.out.println("Crypto key : " + k);
		System.out.println("Extra      : " + e);
		System.out.println(
		        "Doc name   : " + (docName == null ? "none" : docName));
		System.out.print("Meta strings: ");
		if (metaStr == null) {
			System.err.println("none");
		} else
			for (int i = 0; i < metaStr.length; i++) {
				System.err.print(metaStr[i]);
				if (i == metaStr.length - 1) {
					System.err.println();
				} else {
					System.err.print(", ");
				}
			}
	}

	public String getGuessableKey() {
		return getDocName();
	}

	public String getDocName() {
		return docName;
	}

	public String getMetaString() {
		return (metaStr == null || metaStr.length == 0 ? null : metaStr[0]);
	}

	public String[] getAllMetaStrings() {
		return metaStr;
	}

	public byte[] getKeyVal() {
		return getRoutingKey();
	}

	public byte[] getRoutingKey() {
		return routingKey;
	}

	public byte[] getCryptoKey() {
		return cryptoKey;
	}

	public String getKeyType() {
		return keyType;
	}

	/**
	 * Returns a copy of this URI with the first meta string removed.
	 */
	public FreenetURI popMetaString() {
		String[] newMetaStr = null;
		if (metaStr != null && metaStr.length > 1) {
			newMetaStr = new String[metaStr.length - 1];
			System.arraycopy(metaStr, 1, newMetaStr, 0, newMetaStr.length);
		}
		return setMetaString(newMetaStr);
	}

	/**
	 * Returns a copy of this URI with the given string added as a new meta string.
	 */
	public FreenetURI pushMetaString(String name) {
		String[] newMetaStr;
		if(metaStr == null)
			newMetaStr = new String[] { name };
		else {
			newMetaStr = new String[metaStr.length+1];
			System.arraycopy(metaStr, 0, newMetaStr, 0, metaStr.length);
		}
		return setMetaString(newMetaStr);
	}
	
	/**
	 * Returns a copy of this URI with the those meta strings appended.
	 */
	public FreenetURI addMetaStrings(String[] strs) {
		if (strs == null)
			return this; // legal noop, since getMetaStrings can return null
		String[] newMetaStr;
		if (metaStr == null)
			return setMetaString(strs);
		else {
			// metaStr could be null... couldn't it? I don't really know this
			// file..
			// - amphibian
			int curLen = (metaStr == null) ? 0 : metaStr.length;
			newMetaStr = new String[curLen + strs.length];
			if (metaStr != null)
				System.arraycopy(metaStr, 0, newMetaStr, 0, metaStr.length);
			System.arraycopy(strs, 0, newMetaStr, metaStr.length, strs.length);
			return setMetaString(strs);
		}
	}

	/**
	 * Returns a copy of this URI with a new Document name set.
	 */
	public FreenetURI setDocName(String name) {
		return new FreenetURI(
			keyType,
			name,
			metaStr,
			routingKey,
			cryptoKey,
			extra);

	}

	public FreenetURI setMetaString(String[] newMetaStr) {
		return new FreenetURI(
			keyType,
			docName,
			newMetaStr,
			routingKey,
			cryptoKey,
			extra);
	}

	public String toString() {
		return toString(true);
	}

	public String toString(boolean prefix) {
		StringBuffer b;
		if (prefix)
			b = new StringBuffer("freenet:");
		else
			b = new StringBuffer();

		b.append(keyType).append('@');

		if (!"KSK".equals(keyType)) {
			if (routingKey != null)
				b.append(Base64.encode(routingKey));
			if (cryptoKey != null)
				b.append(',').append(Base64.encode(cryptoKey));
			if (extra != null)
			    b.append(',').append(Base64.encode(extra));
			if (docName != null)
				b.append('/');
		}

		if (docName != null)
			b.append(docName);
		if(keyType.equals("USK")) {
			b.append('/');
			b.append(suggestedEdition);
		}
		if (metaStr != null) {
			for (int i = 0; i < metaStr.length; i++) {
				b.append("/").append(metaStr[i]);
			}
		}
		return b.toString();
	}

	public static void main(String[] args) throws Exception {
		(new FreenetURI(args[0])).decompose();
	}

    public byte[] getExtra() {
        return extra;
    }

	public LinkedList listMetaStrings() {
		LinkedList l = new LinkedList();
		if(metaStr != null) {
			for(int i=0;i<metaStr.length;i++)
				l.addLast(metaStr[i]);
		}
		return l;
	}
	
	static final byte CHK = 1;
	static final byte SSK = 2;
	static final byte KSK = 3;
	static final byte USK = 4;
	
	public static FreenetURI readFullBinaryKeyWithLength(DataInputStream dis) throws IOException {
		int len = dis.readShort();
		byte[] buf = new byte[len];
		dis.readFully(buf);
		Logger.minor(FreenetURI.class, "Read "+len+" bytes for key");
		return fromFullBinaryKey(buf);
	}
	
	public static FreenetURI fromFullBinaryKey(byte[] buf) throws IOException {
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bais);
		return readFullBinaryKey(dis);
	}
	
	public static FreenetURI readFullBinaryKey(DataInputStream dis) throws IOException {
		byte type = dis.readByte();
		String keyType;
		if(type == CHK)
			keyType = "CHK";
		else if(type == SSK)
			keyType = "SSK";
		else if(type == KSK)
			keyType = "KSK";
		else
			throw new MalformedURLException("Unrecognized type "+type);
		byte[] routingKey = null;
		byte[] cryptoKey = null;
		byte[] extra = null;
		if(type == CHK || type == SSK) {
			// routingKey is a hash, so is exactly 32 bytes
			routingKey = new byte[32];
			dis.readFully(routingKey);
			// cryptoKey is a 256 bit AES key, so likewise
			cryptoKey = new byte[32];
			dis.readFully(cryptoKey);
			// Number of bytes of extra depends on key type
			int extraLen;
			if(type == CHK)
				extraLen = ClientCHK.EXTRA_LENGTH;
			else //if(type == SSK)
				extraLen = ClientSSK.EXTRA_LENGTH;
			extra = new byte[extraLen];
			dis.readFully(extra);
		}
		String docName = null;
		if(type != CHK)
			docName = dis.readUTF();
		int count = dis.readInt();
		String[] metaStrings = new String[count];
		for(int i=0;i<metaStrings.length;i++) metaStrings[i] = dis.readUTF();
		return new FreenetURI(keyType, docName, metaStrings, routingKey, cryptoKey, extra);
	}

	/**
	 * Write a binary representation of this URI, with a short length, so it can be passed over if necessary.
	 * @param dos The stream to write to.
	 * @throws MalformedURLException If the key could not be written because of inconsistencies or other
	 * problems in the key itself.
	 * @throws IOException If an error occurred while writing the key.
	 */
	public void writeFullBinaryKeyWithLength(DataOutputStream dos) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream ndos = new DataOutputStream(baos);
		writeFullBinaryKey(ndos);
		ndos.close();
		byte[] data = baos.toByteArray();
		if(data.length > Short.MAX_VALUE)
			throw new MalformedURLException("Full key too long: "+data.length+" - "+this);
		dos.writeShort((short)data.length);
		Logger.minor(this, "Written "+data.length+" bytes");
		dos.write(data);
	}

	/**
	 * Write a binary representation of this URI.
	 * @param dos The stream to write to.
	 * @throws MalformedURLException If the key could not be written because of inconsistencies or other
	 * problems in the key itself.
	 * @throws IOException If an error occurred while writing the key.
	 */
	private void writeFullBinaryKey(DataOutputStream dos) throws IOException {
		if(keyType.equals("CHK")) {
			dos.writeByte(CHK);
		} else if(keyType.equals("SSK")) {
			dos.writeByte(SSK);
		} else if(keyType.equals("KSK")) {
			dos.writeByte(KSK);
		} else if(keyType.equals("USK")) {
			throw new MalformedURLException("Cannot write USKs as binary keys");
		} else
			throw new MalformedURLException("Cannot write key of type "+keyType+" - do not know how");
		if(!keyType.equals("KSK")) {
			if(routingKey.length != 32)
				throw new MalformedURLException("Routing key must be of length 32");
			dos.write(routingKey);
			if(cryptoKey.length != 32)
				throw new MalformedURLException("Crypto key must be of length 32");
			dos.write(cryptoKey);
			if(keyType.equals("CHK") && extra.length != ClientCHK.EXTRA_LENGTH)
				throw new MalformedURLException("Wrong number of extra bytes for CHK");
			if(keyType.equals("SSK") && extra.length != ClientSSK.EXTRA_LENGTH)
				throw new MalformedURLException("Wrong number of extra bytes for SSK");
			dos.write(extra);
		}
		if(!keyType.equals("CHK"))
			dos.writeUTF(docName);
		if(metaStr != null) {
			dos.writeInt(metaStr.length);
			for(int i=0;i<metaStr.length;i++)
				dos.writeUTF(metaStr[i]);
		} else
			dos.writeInt(0);
	}

	/** Get suggested edition. Only valid for USKs. */
	public long getSuggestedEdition() {
		if(keyType.equals("USK"))
			return suggestedEdition;
		else throw new IllegalArgumentException("Not a USK requesting suggested edition");
	}
}
