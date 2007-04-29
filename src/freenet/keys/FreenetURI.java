/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.Vector;

import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.client.InserterException;

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
 * where KeyType is the TLA of the key (currently USK, SSK, KSK, or CHK). If
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
 * <p>
 * When constructing a FreenetURI with a String argument, it is now legal for CHK keys
 * to have a '.extension' tail, eg 'CHK@blahblahblah.html'. The constructor will simply
 * chop off at the first dot.
 * </p>
 * REDFLAG: Old code has a FieldSet, and the ability to put arbitrary metadata
 * in through name/value pairs. Do we want this?
 */
public class FreenetURI implements Cloneable{

	private final String keyType, docName;
	private final String[] metaStr;
	private final byte[] routingKey, cryptoKey, extra;
	private final long suggestedEdition; // for USKs
	private boolean hasHashCode;
	private int hashCode;
	
	public int hashCode() {
		if(hasHashCode) return hashCode;
		int x = keyType.hashCode();
		if(docName != null) x ^= docName.hashCode();
		if(metaStr != null) {
			for(int i=0;i<metaStr.length;i++)
				x ^= metaStr[i].hashCode();
		}
		if(routingKey != null)
			x ^= Fields.hashCode(routingKey);
		if(cryptoKey != null)
			x ^= Fields.hashCode(cryptoKey);
		if(extra != null)
			x ^= Fields.hashCode(extra);
		if(keyType.equals("USK"))
			x ^= suggestedEdition;
		hashCode = x;
		hasHashCode = true;
		return x;
	}

	public boolean equals(Object o) {
		if(!(o instanceof FreenetURI))
			return false;
		else {
			FreenetURI f = (FreenetURI)o;
			if(!keyType.equals(f.keyType)) return false;
			if(keyType.equals("USK")) {
				if(!(suggestedEdition == f.suggestedEdition)) return false;
			}
			if((docName == null) ^ (f.docName == null)) return false;
			if((metaStr == null) ^ (f.metaStr == null)) return false;
			if((routingKey == null) ^ (f.routingKey == null)) return false;
			if((cryptoKey == null) ^ (f.cryptoKey == null)) return false;
			if((extra == null) ^ (f.extra == null)) return false;
			if((docName != null) && !docName.equals(f.docName)) return false;
			if((metaStr != null) && !Arrays.equals(metaStr, f.metaStr)) return false;
			if((routingKey != null) && !Arrays.equals(routingKey, f.routingKey)) return false;
			if((cryptoKey != null) && !Arrays.equals(cryptoKey, f.cryptoKey)) return false;
			if((extra != null) && !Arrays.equals(extra, f.extra)) return false;
			return true;
		}
	}

	public final Object clone() {
		return new FreenetURI(this);
	}
	
	public FreenetURI(FreenetURI uri) {
		keyType = uri.keyType;
		docName = uri.docName;
		metaStr = new String[uri.metaStr.length];
        System.arraycopy(uri.metaStr, 0, metaStr, 0, metaStr.length);
        if(uri.routingKey != null) {
			routingKey = new byte[uri.routingKey.length];
			System.arraycopy(uri.routingKey, 0, routingKey, 0, routingKey.length);
		} else {
			routingKey = null;
		}
		if(uri.cryptoKey != null) {
			cryptoKey = new byte[uri.cryptoKey.length];
			System.arraycopy(uri.cryptoKey, 0, cryptoKey, 0, cryptoKey.length);
		} else {
			cryptoKey = null;
		}
		if(uri.extra != null) {
			extra = new byte[uri.extra.length];
			System.arraycopy(uri.extra, 0, extra, 0, extra.length);
		} else {
			extra = null;
		}
		this.suggestedEdition = uri.suggestedEdition;
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
		this.suggestedEdition = -1;
	}

	public FreenetURI(
			String keyType,
			String docName,
			String[] metaStr,
			byte[] routingKey,
			byte[] cryptoKey, byte[] extra2,
			long suggestedEdition) {
			this.keyType = keyType.trim().toUpperCase();
			this.docName = docName;
			this.metaStr = metaStr;
			this.routingKey = routingKey;
			this.cryptoKey = cryptoKey;
			this.extra = extra2;
			this.suggestedEdition = suggestedEdition;
		}

	public FreenetURI(String URI) throws MalformedURLException {
		if (URI == null) {
			throw new MalformedURLException("No URI specified");
		} else
			URI = URI.trim();
		
		if(URI.indexOf('@') < 0 || URI.indexOf('/') < 0) {
			// Encoded URL?
			try {
				URI=URLDecoder.decode(URI, false);
			} catch (URLEncodedFormatException e) {
				throw new MalformedURLException("Invalid URI: no @ or /, or @ or / is escaped but there are invalid escapes");
			}
		}

		// Strip http:// prefix
		URI = URI.replaceAll("^http://.*/+", "");
		
		// check scheme
		int colon = URI.indexOf(':');
		if ((colon != -1)
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
		Vector sv = null;
		int slash2;
		sv = new Vector();
		while ((slash2 = URI.lastIndexOf("/")) != -1) {
			String s;
			try {
				s = URLDecoder.decode(URI.substring(slash2 + "/".length()), true);
			} catch (URLEncodedFormatException e) {
				MalformedURLException ue = new MalformedURLException(e.toString());
				ue.initCause(e);
				throw ue;
			}
			if (s != null)
				sv.addElement(s);
			URI = URI.substring(0, slash2);
		}
		
		// sv is *backwards*
		// this makes for more efficient handling
		
		boolean isSSK = "SSK".equals(keyType);
		boolean isUSK = "USK".equals(keyType);
		boolean isKSK = "KSK".equals(keyType);
		
		if(isSSK || isUSK) {
			
			if(sv.isEmpty())
				throw new MalformedURLException("No docname for "+keyType);
			docName = (String) sv.remove(sv.size()-1);
			if(isUSK) {
				if(sv.isEmpty()) throw new MalformedURLException("No suggested edition number for USK");
				try {
					suggestedEdition = Long.parseLong((String)sv.remove(sv.size()-1));
				} catch (NumberFormatException e) {
					MalformedURLException e1 = new MalformedURLException("Invalid suggested edition: "+e);
					e1.initCause(e);
					throw e1;
				}
			} else
				suggestedEdition = -1;
		} else if(isKSK) {
			// Deal with KSKs
			docName = URI;
			suggestedEdition = -1;
		} else {
			// docName not necessary, nor is it supported, for CHKs.
			docName = null;
			suggestedEdition = -1;
		}
		
		if (!sv.isEmpty()) {
			metaStr = new String[sv.size()];
			for (int i = 0; i < metaStr.length; i++) {
				metaStr[i] = (String) sv.elementAt(metaStr.length - 1 - i);
				if(metaStr[i] == null) throw new NullPointerException();
			}
		} else {
			metaStr = null;
		}

		if(isKSK) {
			routingKey = extra = cryptoKey = null;
			return;
		}
		
        // strip 'file extensions' from CHKs
        // added by aum (david@rebirthing.co.nz)
        if ("CHK".equals(keyType)) {
            URI = URI.split("[.]")[0];
        }

		// URI now contains: routingKey[,cryptoKey][,metaInfo]
		StringTokenizer st = new StringTokenizer(URI, ",");
		try {
			if (st.hasMoreTokens()) {
				routingKey = Base64.decode(st.nextToken());
			} else {
				routingKey = cryptoKey = extra = null;
				return;
			}
			if (!st.hasMoreTokens()) {
				cryptoKey = extra = null;
				return;
			}

			// Can be cryptokey or name-value pair.
			String t = st.nextToken();
			cryptoKey = Base64.decode(t);
			if (!st.hasMoreTokens()) {
				extra = null;
				return;
			}
			extra = Base64.decode(st.nextToken());

		} catch (IllegalBase64Exception e) {
			throw new MalformedURLException("Invalid Base64 quantity: " + e);
		}
	}

	/** USK constructor from components. */
	public FreenetURI(byte[] pubKeyHash, byte[] cryptoKey, byte[] extra, String siteName, long suggestedEdition2) {
		this.keyType = "USK";
		this.routingKey = pubKeyHash;
		this.cryptoKey = cryptoKey;
		this.extra = extra;
		this.docName = siteName;
		this.suggestedEdition = suggestedEdition2;
		metaStr = null;
	}

	public void decompose() {
		String r = routingKey == null ? "none" : HexUtil.bytesToHex(routingKey);
		String k = cryptoKey == null ? "none" : HexUtil.bytesToHex(cryptoKey);
		String e = extra == null ? "none" : HexUtil.bytesToHex(extra);
		System.out.println("FreenetURI" + this);
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
		return ((metaStr == null) || (metaStr.length == 0)) ? null : metaStr[0];
	}

	public String lastMetaString() {
		return ((metaStr == null ) || (metaStr.length == 0)) ? null : metaStr[metaStr.length-1]; 
	}
	
	public String[] getAllMetaStrings() {
		return metaStr;
	}
	
	public boolean hasMetaStrings() {
		return !(metaStr == null || metaStr.length == 0);
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
		if ((metaStr != null) && (metaStr.length > 1)) {
			newMetaStr = new String[metaStr.length - 1];
			System.arraycopy(metaStr, 1, newMetaStr, 0, newMetaStr.length);
		}
		return setMetaString(newMetaStr);
	}

	public FreenetURI dropLastMetaStrings(int i) {
		String[] newMetaStr = null;
		if ((metaStr != null) && (metaStr.length > 1)) {
			if(i > metaStr.length) i = metaStr.length;
			newMetaStr = new String[metaStr.length - i];
			System.arraycopy(metaStr, 0, newMetaStr, 0, newMetaStr.length);
		}
		return setMetaString(newMetaStr);
	}

	/**
	 * Returns a copy of this URI with the given string added as a new meta string.
	 */
	public FreenetURI pushMetaString(String name) {
		String[] newMetaStr;
		if(name == null) throw new NullPointerException();
		if(metaStr == null)
			newMetaStr = new String[] { name };
		else {
			newMetaStr = new String[metaStr.length+1];
			System.arraycopy(metaStr, 0, newMetaStr, 0, metaStr.length);
			newMetaStr[metaStr.length] = name;
		}
		return setMetaString(newMetaStr);
	}
	
	/**
	 * Returns a copy of this URI with the those meta strings appended.
	 */
	public FreenetURI addMetaStrings(String[] strs) {
		if (strs == null)
			return this; // legal noop, since getMetaStrings can return null
		for(int i=0;i<strs.length;i++) if(strs[i] == null) throw new NullPointerException("element "+i+" of "+strs.length+" is null");
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

	public FreenetURI addMetaStrings(LinkedList metaStrings) {
		return addMetaStrings((String[])metaStrings.toArray(new String[metaStrings.size()]));
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
			extra,
			suggestedEdition);

	}

	public FreenetURI setMetaString(String[] newMetaStr) {
		return new FreenetURI(
			keyType,
			docName,
			newMetaStr,
			routingKey,
			cryptoKey,
			extra,
			suggestedEdition);
	}

	public String toString() {
		return toString(false, false);
	}
	
	public String toACIIString() {
		return toString(true, true);
	}

	public String toString(boolean prefix, boolean pureAscii) {
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
			b.append(URLEncoder.encode(docName, "/", pureAscii));
		if(keyType.equals("USK")) {
			b.append('/');
			b.append(suggestedEdition);
		}
		if (metaStr != null) {
			for (int i = 0; i < metaStr.length; i++) {
				b.append('/').append(URLEncoder.encode(metaStr[i], "/", pureAscii));
			}
		}
		return b.toString();
	}
	
	public String toShortString() {
		StringBuffer b = new StringBuffer();;
		
		b.append(keyType).append('@');
		
		if (!"KSK".equals(keyType)) {
			b.append("...");
			if (docName != null)
				b.append('/');
		}
		
		if (docName != null)
			b.append(URLEncoder.encode(docName, "/", false));
		if(keyType.equals("USK")) {
			b.append('/');
			b.append(suggestedEdition);
		}
		if (metaStr != null) {
			for (int i = 0; i < metaStr.length; i++) {
				b.append('/').append(URLEncoder.encode(metaStr[i], "/", false));
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
		if(Logger.shouldLog(Logger.MINOR, FreenetURI.class))
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
		if((type == CHK) || (type == SSK)) {
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
		if(Logger.shouldLog(Logger.MINOR, FreenetURI.class))
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
			if(keyType.equals("CHK") && (extra.length != ClientCHK.EXTRA_LENGTH))
				throw new MalformedURLException("Wrong number of extra bytes for CHK");
			if(keyType.equals("SSK") && (extra.length != ClientSSK.EXTRA_LENGTH))
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

	public String getPreferredFilename() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		Logger.minor(this, "Getting preferred filename for "+this);
		Vector names = new Vector();
		if(keyType != null && (keyType.equals("KSK") || keyType.equals("SSK"))) {
			if(logMINOR) Logger.minor(this, "Adding docName: "+docName);
			names.add(docName);
		}
		if(metaStr != null)
			for(int i=0;i<metaStr.length;i++) {
				if(logMINOR) Logger.minor(this, "Adding metaString "+i+": "+metaStr[i]);
				names.add(metaStr[i]);
			}
		StringBuffer out = new StringBuffer();
		for(int i=0;i<names.size();i++) {
			String s = (String) names.get(i);
			if(logMINOR) Logger.minor(this, "name "+i+" = "+s);
			s = sanitize(s);
			if(logMINOR) Logger.minor(this, "Sanitized name "+i+" = "+s);
			if(s.length() > 0) {
				if(out.length() > 0)
					out.append('-');
				out.append(s);
			}
		}
		if(logMINOR) Logger.minor(this, "out = "+out.toString());
		if(out.length() == 0) {
			if(routingKey != null) {
				if(logMINOR) Logger.minor(this, "Returning base64 encoded routing key");
				return Base64.encode(routingKey);
			}
			return "unknown";
		}
		return out.toString();
	}

	private String sanitize(String s) {
		StringBuffer sb = new StringBuffer(s.length());
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if((c == '/') || (c == '\\') || (c == '%') || (c == '>') || (c == '<') || (c == ':') || (c == '\'') || (c == '\"'))
				continue;
			if(Character.isDigit(c))
				sb.append(c);
			else if(Character.isLetter(c))
				sb.append(c);
			else if(Character.isWhitespace(c))
				sb.append(' ');
			else if((c == '-') || (c == '_') || (c == '.'))
				sb.append(c);
		}
		return sb.toString();
	}

	public FreenetURI setSuggestedEdition(long newEdition) {
		return new FreenetURI(
				keyType,
				docName,
				metaStr,
				routingKey,
				cryptoKey,
				extra,
				newEdition);
	}

	public FreenetURI setKeyType(String newKeyType) {
		return new FreenetURI(
				newKeyType,
				docName,
				metaStr,
				routingKey,
				cryptoKey,
				extra,
				suggestedEdition);
	}
	
	public void checkInsertURI() throws InserterException {
		if(metaStr != null && metaStr.length > 0)
			throw new InserterException(InserterException.META_STRINGS_NOT_SUPPORTED,this);
	}
	
	public static void checkInsertURI(FreenetURI uri) throws InserterException { uri.checkInsertURI(); }

	public URI toRelativeURI() throws URISyntaxException {
		// Single-argument constructor used because it preserves encoded /'es in path.
		// Hence we can have slashes, question marks etc in the path, but they are encoded.
		return new URI('/' + toString(false, false));
	}

	public URI toURI(String basePath) throws URISyntaxException {
		return new URI(basePath + toString(false, false));
	}

}
