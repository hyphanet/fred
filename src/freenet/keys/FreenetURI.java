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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.Logger.LogLevel;
import freenet.support.io.FileUtil;

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
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FreenetURI implements Cloneable, Comparable<FreenetURI> {
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private final String keyType,  docName;
	/** The meta-strings, in the order they are given. Typically we will
	 * construct the base key from the key type, routing key, extra, and
	 * document name (SSK@blah,blah,blah/filename, CHK@blah,blah,blah,
	 * KSK@filename or USK@blah,blah,blah/filename/20), fetch it, discover
	 * that it is a manifest, and look up the first meta-string. If this is
	 * the final data, we use that (and complain if there are meta-strings
	 * left), else we look up the next meta-string in the manifest, and so
	 * on. This is executed by SingleFileFetcher. */
	private final String[] metaStr;
	/* for SSKs, routingKey is actually the pkHash. the actual routing key is
	 * calculated in NodeSSK and is a function of pkHash and the docName
	 */
	private final byte[] routingKey,  cryptoKey,  extra;
	private final long suggestedEdition; // for USKs
	private boolean hasHashCode;
	private int hashCode;
//	private final int uniqueHashCode;
	static final String[] VALID_KEY_TYPES =
		new String[]{"CHK", "SSK", "KSK", "USK"};

	@Override
	public synchronized int hashCode() {
		if(hasHashCode)
			return hashCode;
		int x = keyType.hashCode();
		if(docName != null)
			x ^= docName.hashCode();
		if(metaStr != null)
			for(int i = 0; i < metaStr.length; i++)
				x ^= metaStr[i].hashCode();
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

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		if(!(o instanceof FreenetURI))
			return false;
		else {
			FreenetURI f = (FreenetURI) o;
			if(!keyType.equals(f.keyType))
				return false;
			if(keyType.equals("USK"))
				if(!(suggestedEdition == f.suggestedEdition))
					return false;
			if((docName == null) ^ (f.docName == null))
				return false;
			if((metaStr == null || metaStr.length == 0) ^ (f.metaStr == null || f.metaStr.length == 0))
				return false;
			if((routingKey == null) ^ (f.routingKey == null))
				return false;
			if((cryptoKey == null) ^ (f.cryptoKey == null))
				return false;
			if((extra == null) ^ (f.extra == null))
				return false;
			if((docName != null) && !docName.equals(f.docName))
				return false;
			if((metaStr != null) && !Arrays.equals(metaStr, f.metaStr))
				return false;
			if((routingKey != null) && !Arrays.equals(routingKey, f.routingKey))
				return false;
			if((cryptoKey != null) && !Arrays.equals(cryptoKey, f.cryptoKey))
				return false;
			if((extra != null) && !Arrays.equals(extra, f.extra))
				return false;
			return true;
		}
	}

	/** Is the keypair (the routing key and crypto key) the same as the
	 * given key?
	 * @return False if there is no routing key or no crypto key (CHKs,
	 * SSKs, USKs have them, KSKs don't), or if the keys don't have the
	 * same crypto key and routing key.
	 */
	public boolean equalsKeypair(FreenetURI u2) {
		if((routingKey != null) && (cryptoKey != null))
			return Arrays.equals(routingKey, u2.routingKey) && Arrays.equals(cryptoKey, u2.cryptoKey);

		return false;
	}

	@Override
	public final FreenetURI clone() {
		return new FreenetURI(this);
	}

	public FreenetURI(FreenetURI uri) {
//		this.uniqueHashCode = super.hashCode();
		if(uri.keyType == null) throw new NullPointerException();
		keyType = uri.keyType;
		docName = uri.docName;
		if(uri.metaStr != null) {
			metaStr = uri.metaStr.clone();
		} else metaStr = null;
		if(uri.routingKey != null) {
			routingKey = uri.routingKey.clone();
		} else
			routingKey = null;
		if(uri.cryptoKey != null) {
			cryptoKey = uri.cryptoKey.clone();
		} else
			cryptoKey = null;
		if(uri.extra != null) {
			extra = uri.extra.clone();
		} else
			extra = null;
		this.suggestedEdition = uri.suggestedEdition;
		if(logDEBUG) Logger.debug(this, "Copied: "+toString()+" from "+uri.toString(), new Exception("debug"));
	}
	
	boolean noCacheURI = false;
	
	/** Optimize for memory. */
	public FreenetURI intern() {
		boolean changedAnything = false;
		byte[] x = extra;
		if(keyType.equals("CHK"))
			x = ClientCHK.internExtra(x);
		else
			x = ClientSSK.internExtra(x);
		if(x != extra) changedAnything = true;
		String[] newMetaStr = null;
		if(metaStr != null) {
			newMetaStr = new String[metaStr.length];
			for(int i=0;i<metaStr.length;i++) {
				newMetaStr[i] = metaStr[i].intern();
				if(metaStr[i] != newMetaStr[i]) changedAnything = true;
			}
		}
		String dn = docName == null ? null : docName.intern();
		if(dn != docName) changedAnything = true;
		if(!changedAnything) {
			noCacheURI = true;
			return this;
		}
		FreenetURI u = new FreenetURI(keyType, dn, newMetaStr, routingKey, cryptoKey, extra);
		u.noCacheURI = true;
		return u;
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
			(metaStr == null ? (String[]) null : new String[]{metaStr}),
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
//		this.uniqueHashCode = super.hashCode();
		this.keyType = keyType.trim().toUpperCase().intern();
		this.docName = docName;
		this.metaStr = metaStr;
		this.routingKey = routingKey;
		if(routingKey != null && keyType.equals("CHK") && routingKey.length != 32)
			throw new IllegalArgumentException("Bad URI: Routing key should be 32 bytes");
		this.cryptoKey = cryptoKey;
		if(cryptoKey != null && cryptoKey.length != 32)
			throw new IllegalArgumentException("Bad URI: Crypto key should be 32 bytes");
		this.extra = extra2;
		this.suggestedEdition = -1;
		if (logDEBUG) Logger.minor(this, "Created from components: "+toString(), new Exception("debug"));
	}

	public FreenetURI(
		String keyType,
		String docName,
		String[] metaStr,
		byte[] routingKey,
		byte[] cryptoKey, byte[] extra2,
		long suggestedEdition) {
//		this.uniqueHashCode = super.hashCode();
		this.keyType = keyType.trim().toUpperCase().intern();
		this.docName = docName;
		this.metaStr = metaStr;
		this.routingKey = routingKey;
		if(routingKey != null && keyType.equals("CHK") && routingKey.length != 32)
			throw new IllegalArgumentException("Bad URI: Routing key should be 32 bytes");
		this.cryptoKey = cryptoKey;
		if(cryptoKey != null && cryptoKey.length != 32)
			throw new IllegalArgumentException("Bad URI: Crypto key should be 32 bytes");
		this.extra = extra2;
		this.suggestedEdition = suggestedEdition;
		if (logDEBUG) Logger.minor(this, "Created from components (B): "+toString(), new Exception("debug"));
	}

	// Strip http:// and freenet: prefix
	protected final static Pattern URI_PREFIX = Pattern.compile("^(http://[^/]+/+)?(freenet:)?");

	public FreenetURI(String URI) throws MalformedURLException {
		this(URI, false);
	}
	
	/**
	 * Create a FreenetURI from its string form. May or may not have a
	 * freenet: prefix.
	 * @throws MalformedURLException If the string could not be parsed.
	 */
	public FreenetURI(String URI, boolean noTrim) throws MalformedURLException {
//		this.uniqueHashCode = super.hashCode();
		if(URI == null)
			throw new MalformedURLException("No URI specified");

		if(!noTrim)
			URI = URI.trim();
		
		// Strip ?max-size, ?type etc.
		// Un-encoded ?'s are illegal.
		int x = URI.indexOf('?');
		if(x > -1)
			URI = URI.substring(0, x);
			
		if(URI.indexOf('@') < 0 || URI.indexOf('/') < 0)
			// Encoded URL?
			try {
				URI = URLDecoder.decode(URI, false);
			} catch(URLEncodedFormatException e) {
				throw new MalformedURLException("Invalid URI: no @ or /, or @ or / is escaped but there are invalid escapes");
			}

		URI = URI_PREFIX.matcher(URI).replaceFirst("");

		// decode keyType
		int atchar = URI.indexOf('@');
		if(atchar == -1)
			throw new MalformedURLException("There is no @ in that URI! (" + URI + ')');

		String _keyType = URI.substring(0, atchar).toUpperCase();
		URI = URI.substring(atchar + 1);

		boolean validKeyType = false;
		for(int i = 0; i < VALID_KEY_TYPES.length; i++) {
			if (_keyType.equals(VALID_KEY_TYPES[i])) {
				validKeyType = true;
				_keyType = VALID_KEY_TYPES[i];
				break;
			}
		}
		keyType = _keyType;
		if(!validKeyType)
			throw new MalformedURLException("Invalid key type: " + keyType);

		boolean isSSK = "SSK".equals(keyType);
		boolean isUSK = "USK".equals(keyType);
		boolean isKSK = "KSK".equals(keyType);

		// decode metaString
		ArrayList<String> sv = null;
		int slash2;
		sv = new ArrayList<String>();
		if (isKSK) URI = "/" + URI; // ensure that KSK docNames are decoded
		while ((slash2 = URI.lastIndexOf('/')) != -1) {
			String s;
			try {
				s = URLDecoder.decode(URI.substring(slash2 + 1 /* "/".length() */), true);
			} catch(URLEncodedFormatException e) {
				throw (MalformedURLException)new MalformedURLException(e.toString()).initCause(e);
			}
			if(s != null)
				sv.add(s);
			URI = URI.substring(0, slash2);
		}

		// sv is *backwards*
		// this makes for more efficient handling

		// SSK@ = create a random SSK
		if(sv.isEmpty() && (isUSK || isKSK))
			throw new MalformedURLException("No docname for " + keyType);
		
		if((isSSK || isUSK || isKSK) && !sv.isEmpty()) {

			docName = sv.remove(sv.size() - 1);
			if(isUSK) {
				if(sv.isEmpty())
					throw new MalformedURLException("No suggested edition number for USK");
				try {
					suggestedEdition = Long.parseLong(sv.remove(sv.size() - 1));
				} catch(NumberFormatException e) {
					throw (MalformedURLException)new MalformedURLException("Invalid suggested edition: " + e).initCause(e);
				}
			} else
				suggestedEdition = -1;
		} else {
			// docName not necessary, nor is it supported, for CHKs.
			docName = null;
			suggestedEdition = -1;
		}

		if(!sv.isEmpty()) {
			metaStr = new String[sv.size()];
			for(int i = 0; i < metaStr.length; i++) {
				metaStr[i] = sv.get(metaStr.length - 1 - i).intern();
				if(metaStr[i] == null)
					throw new NullPointerException();
			}
		} else
			metaStr = null;

		if(isKSK) {
			routingKey = extra = cryptoKey = null;
			return;
		}

		// strip 'file extensions' from CHKs
		// added by aum (david@rebirthing.co.nz)
		if("CHK".equals(keyType)) {
			int idx = URI.lastIndexOf('.');
			if(idx != -1)
				URI = URI.substring(0, idx);
		}

		// URI now contains: routingKey[,cryptoKey][,metaInfo]
		StringTokenizer st = new StringTokenizer(URI, ",");
		try {
			if(st.hasMoreTokens()) {
				routingKey = Base64.decode(st.nextToken());
				if(routingKey.length != 32 && keyType.equals("CHK"))
					throw new MalformedURLException("Bad URI: Routing key should be 32 bytes long");
			} else {
				routingKey = cryptoKey = extra = null;
				return;
			}
			if(!st.hasMoreTokens()) {
				cryptoKey = extra = null;
				return;
			}

			// Can be cryptokey or name-value pair.
			String t = st.nextToken();
			cryptoKey = Base64.decode(t);
			if(cryptoKey.length != 32)
				throw new MalformedURLException("Bad URI: Routing key should be 32 bytes long");
			if(!st.hasMoreTokens()) {
				extra = null;
				return;
			}
			extra = Base64.decode(st.nextToken());

		} catch(IllegalBase64Exception e) {
			throw new MalformedURLException("Invalid Base64 quantity: " + e);
		}
		if (logDEBUG) Logger.debug(this, "Created from parse: "+toString()+" from "+URI, new Exception("debug"));
	}

	/** USK constructor from components. */
	public FreenetURI(byte[] pubKeyHash, byte[] cryptoKey, byte[] extra, String siteName, long suggestedEdition2) {
//		this.uniqueHashCode = super.hashCode();
		this.keyType = "USK";
		this.routingKey = pubKeyHash;
		// Don't check routingKey as it could be an insertable USK
		this.cryptoKey = cryptoKey;
		if(cryptoKey != null && cryptoKey.length != 32)
			throw new IllegalArgumentException("Bad URI: Crypto key should be 32 bytes");
		this.extra = extra;
		this.docName = siteName;
		this.suggestedEdition = suggestedEdition2;
		metaStr = null;
		if (logDEBUG) Logger.minor(this, "Created from components (USK): "+toString(), new Exception("debug"));
	}

	/** Dump the individual components of the key to System.out. */
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
		if(metaStr == null)
			System.out.println("none");
		else
			System.out.println(Arrays.asList(metaStr).toString());
	}

	public String getGuessableKey() {
		return getDocName();
	}

	/** Get the document name. For a KSK this is everything from the @ to
	 * the first slash or the end of the key. For an SSK this is everything
	 * from the slash to the next slash or the end of the key. CHKs don't
	 * have a doc name, they only have meta strings. */
	public String getDocName() {
		return docName;
	}

	/** Get the first meta-string. This is just after the main part of the
	 * key and the doc name. Meta-strings are directory (manifest) lookups
	 * delimited by /'es after the main key and the doc name if any.
	 */
	public String getMetaString() {
		return ((metaStr == null) || (metaStr.length == 0)) ? null : metaStr[0];
	}

	/** Get the last meta string. Meta-strings are directory (manifest)
	 * lookups after the main key and the doc name if any. So the last meta
	 * string, if there is one, is from the last / to the end of the uri
	 * i.e. usually the filename. */
	public String lastMetaString() {
		return ((metaStr == null) || (metaStr.length == 0)) ? null : metaStr[metaStr.length - 1];
	}

	/** Get all the meta strings. Meta strings are directory (manifest)
	 * lookups after the main key and the doc name if any. Examples:
	 *
	 * CHK@blah,blah,blah/filename
	 *
	 * This has a routing key, a crypto key, extra bytes, no document name,
	 * and one meta string "filename"
	 *
	 * SSK@blah,blah,blah/docname/dir/subdir/filename
	 *
	 * This has a routing key, a crypto key, extra bytes, a document name,
	 * and three meta strings "dir", "subdir" and "filename". The SSK
	 * including the docname is turned into a low level Freenet key, which
	 * we fetch. This will produce a metadata document containing a
	 * manifest, within which we look up "dir". This either gives us
	 * another metadata document directly, or a redirect if the dir is
	 * inserted separately. And so on. If it's a container, the files will
	 * be stored, with the metadata, in the container (tar.bz2 or
	 * whatever); the metadata fetched by SSK@blah,blah,blah/docname will
	 * say that there is a container and explain how to fetch it.
	 *
	 * KSK@gpl.txt
	 *
	 * This has no routing key, no crypto key, and no meta strings (but
	 * KSKs *can* have meta strings), but it has a document name.
	 */
	public String[] getAllMetaStrings() {
		return metaStr;
	}

	/** Are there any meta-strings? */
	public boolean hasMetaStrings() {
		return !(metaStr == null || metaStr.length == 0);
	}

	/** Get the routing key. This is the first part of the key after the @
	 * for CHKs, SSKs and USKs. For purposes of FreenetURI, KSKs do not
	 * have a routing key. For CHKs, this is ultimately derived from the
	 * hash of the encrypted data; for SSKs it is the hash of the public
	 * key.
	 */
	public byte[] getRoutingKey() {
		return routingKey;
	}

	/** Get the crypto key. This is the second part of the key after the @
	 * for CHKs, SSKs and USKs. For purposes of FreenetURI, KSKs do not
	 * have a crypto key. For CHKs, this is derived from the hash of the
	 * *original* plaintext data; for SSKs it is a separate key for
	 * decryption. The crypto key is kept on the requesting node and is not
	 * sent over the network - but of course many freesites and other
	 * documents on the network include URIs which do include crypto keys.
	 */
	public byte[] getCryptoKey() {
		return cryptoKey;
	}

	/** Get the key type. CHK, SSK, KSK or USK. Upper case, we normally
	 * use the constants. */
	public String getKeyType() {
		return keyType;
	}

	/**
	 * Returns a copy of this URI with the first meta string removed.
	 */
	public FreenetURI popMetaString() {
		String[] newMetaStr = null;
		if (metaStr != null) {
			final int metaStrLength = metaStr.length;
			if (metaStrLength > 1) {
				newMetaStr = Arrays.copyOf(metaStr, metaStr.length-1);
			}
		}
		return setMetaString(newMetaStr);
	}

	/** Create a new URI with the last few meta-strings dropped.
	 * @param i The number of meta-strings to drop.
	 * @return A new FreenetURI with the specified number of meta-strings
	 * removed from the end.
	 */
	public FreenetURI dropLastMetaStrings(int i) {
		String[] newMetaStr = null;
		if((metaStr != null) && (metaStr.length > i)) {
			newMetaStr = Arrays.copyOf(metaStr, metaStr.length - i);
		}
		return setMetaString(newMetaStr);
	}

	/**
	 * Returns a copy of this URI with the given string appended as a
	 * meta-string.
	 */
	public FreenetURI pushMetaString(String name) {
		String[] newMetaStr;
		if(name == null)
			throw new NullPointerException();
		if(metaStr == null)
			newMetaStr = new String[]{name};
		else {
			newMetaStr = Arrays.copyOf(metaStr, metaStr.length + 1);
			newMetaStr[metaStr.length] = name.intern();
		}
		return setMetaString(newMetaStr);
	}

	/**
	 * Returns a copy of this URI with these meta strings appended.
	 */
	public FreenetURI addMetaStrings(String[] strs) {
		if(strs == null)
			return this; // legal noop, since getMetaStrings can return null
		for(int i = 0; i < strs.length; i++)
			if(strs[i] == null)
				throw new NullPointerException("element " + i + " of " + strs.length + " is null");
		String[] newMetaStr;
		if(metaStr == null)
			return setMetaString(strs);
		else {
			newMetaStr = Arrays.copyOf(metaStr, metaStr.length + strs.length);
			System.arraycopy(strs, 0, newMetaStr, metaStr.length, strs.length);
			return setMetaString(newMetaStr);
		}
	}

	/**
	 * Returns a copy of this URI with these meta strings appended.
	 */
	public FreenetURI addMetaStrings(List<String> metaStrings) {
		return addMetaStrings(metaStrings.toArray(new String[metaStrings.size()]));
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

	/** Returns a copy of this URI with new meta-strings. */
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

	protected String toStringCache;

	/** toString() is equivalent to toString(false, false) but is cached. */
	@Override
	public String toString() {
		if (toStringCache == null)
			toStringCache = toString(false, false)/* + "#"+super.toString()+"#"+uniqueHashCode*/;
		return toStringCache;
	}

    /**
     * @deprecated Use {@link #toASCIIString()} instead
     */
	@Deprecated
    public String toACIIString() {
        return toASCIIString();
    }

	/**
	 * Get the FreenetURI as a pure ASCII string, any non-english
	 * characters as well as any dangerous characters are encoded.
	 * @return
	 */
	public String toASCIIString() {
		return toString(true, true);
	}

	/**
	 * Get the FreenetURI as a string.
	 * @param prefix Whether to include the freenet: prefix.
	 * @param pureAscii If true, encode any non-english characters. If
	 * false, only encode dangerous characters (slashes e.g.).
	 */
	public String toString(boolean prefix, boolean pureAscii) {
		if(keyType == null) {
			// Not activated or something...
			if(logMINOR) Logger.minor(this, "Not activated?? in toString("+prefix+","+pureAscii+")");
			return null;
		}
		StringBuilder b;
		if(prefix)
			b = new StringBuilder("freenet:");
		else
			b = new StringBuilder();

		b.append(keyType).append('@');

		if(!"KSK".equals(keyType)) {
			if(routingKey != null)
				b.append(Base64.encode(routingKey));
			if(cryptoKey != null)
				b.append(',').append(Base64.encode(cryptoKey));
			if(extra != null)
				b.append(',').append(Base64.encode(extra));
			if(docName != null)
				b.append('/');
		}

		if(docName != null)
			b.append(URLEncoder.encode(docName, "/", pureAscii));
		if(keyType.equals("USK")) {
			b.append('/');
			b.append(suggestedEdition);
		}
		if(metaStr != null)
			for(int i = 0; i < metaStr.length; i++) {
				b.append('/').append(URLEncoder.encode(metaStr[i], "/", pureAscii));
			}
		return b.toString();
	}

	/** Encode to a user-friendly, incomplete string with ... replacing some of
	 * the base64. Allow spaces, foreign chars etc. */
	public String toShortString() {
		StringBuilder b = new StringBuilder();

		b.append(keyType).append('@');

		if(!"KSK".equals(keyType)) {
			b.append("...");
			if(docName != null)
				b.append('/');
		}

		if(docName != null)
			b.append(URLEncoder.encode(docName, "/", false, " "));
		if(keyType.equals("USK")) {
			b.append('/');
			b.append(suggestedEdition);
		}
		if(metaStr != null)
			for(int i = 0; i < metaStr.length; i++) {
				b.append('/').append(URLEncoder.encode(metaStr[i], "/", false, " "));
			}
		return b.toString();
	}

	/** Run this class to decompose the argument. */
	public static void main(String[] args) throws Exception {
		(new FreenetURI(args[0])).decompose();
	}

	/** Get the extra bytes. SSKs and CHKs have extra bytes, these come
	 * after the second comma, and specify encryption and hashing
	 * algorithms etc.
	 */
	public byte[] getExtra() {
		return extra;
	}

	/** Get the meta strings as an ArrayList. */
	public ArrayList<String> listMetaStrings() {
		if(metaStr != null) {
			ArrayList<String> l = new ArrayList<String>(metaStr.length);
			for(int i = 0; i < metaStr.length; i++)
				l.add(metaStr[i]);
			return l;
		} else return new ArrayList<String>(0);
	}
	static final byte CHK = 1;
	static final byte SSK = 2;
	static final byte KSK = 3;
	static final byte USK = 4;

	/** Read the binary form of a key, preceded by a short for its length. */
	public static FreenetURI readFullBinaryKeyWithLength(DataInputStream dis) throws IOException {
		int len = dis.readShort();
		byte[] buf = new byte[len];
		dis.readFully(buf);
		if(logMINOR) Logger.minor(FreenetURI.class, "Read " + len + " bytes for key");
		return fromFullBinaryKey(buf);
	}

	/** Create a FreenetURI from the binary form of the key. */
	public static FreenetURI fromFullBinaryKey(byte[] buf) throws IOException {
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bais);
		return readFullBinaryKey(dis);
	}

	/** Create a FreenetURI from the binary form of the key, read from a
	 * stream, with no length.
	 * @throws MalformedURLException If there was a format error in the data.
	 * @throws IOException If a read error occurred */
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
			throw new MalformedURLException("Unrecognized type " + type);
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
			extraLen = (type == CHK ? ClientCHK.EXTRA_LENGTH : ClientSSK.EXTRA_LENGTH);
			extra = new byte[extraLen];
			dis.readFully(extra);
		}
		String docName = null;
		if(type != CHK)
			docName = dis.readUTF();
		int count = dis.readInt();
		String[] metaStrings = new String[count];
		for(int i = 0; i < metaStrings.length; i++)
			metaStrings[i] = dis.readUTF();
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
			throw new MalformedURLException("Full key too long: " + data.length + " - " + this);
		dos.writeShort((short) data.length);
		if(logMINOR)
			Logger.minor(this, "Written " + data.length + " bytes");
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
		if(keyType.equals("CHK"))
			dos.writeByte(CHK);
		else if(keyType.equals("SSK"))
			dos.writeByte(SSK);
		else if(keyType.equals("KSK"))
			dos.writeByte(KSK);
		else if(keyType.equals("USK"))
			throw new MalformedURLException("Cannot write USKs as binary keys");
		else
			throw new MalformedURLException("Cannot write key of type " + keyType + " - do not know how");
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
			for(int i = 0; i < metaStr.length; i++)
				dos.writeUTF(metaStr[i]);
		} else
			dos.writeInt(0);
	}

	/** Get suggested edition. Only valid for USKs. */
	public long getSuggestedEdition() {
		if(keyType.equals("USK"))
			return suggestedEdition;
		else
			throw new IllegalArgumentException("Not a USK requesting suggested edition");
	}

	/** Generate a suggested filename for the URI. This may be constructed
	 * from more than one part of the URI e.g. SSK@blah,blah,blah/sitename/
	 * might return sitename. */
	public String getPreferredFilename() {
		if (logMINOR)
			Logger.minor(this, "Getting preferred filename for " + this);
		ArrayList<String> names = new ArrayList<String>();
		if(keyType != null && (keyType.equals("KSK") || keyType.equals("SSK") || keyType.equals("USK"))) {
			if(logMINOR)
				Logger.minor(this, "Adding docName: " + docName);
			if(docName != null) {
				names.add(docName);
				if(keyType.equals("USK"))
					names.add(Long.toString(suggestedEdition));
			} else if(!keyType.equals("SSK")) {
				// "SSK@" is legal for an upload.
				throw new IllegalStateException("No docName for key of type "+keyType);
			}
		}
		if(metaStr != null)
			for(int i = 0; i < metaStr.length; i++) {
				if(metaStr[i] == null || metaStr[i].equals("")) {
					if(logMINOR)
						Logger.minor(this, "metaString " + i + ": was null or empty");
					continue;
				}
				if(logMINOR)
					Logger.minor(this, "Adding metaString " + i + ": " + metaStr[i]);
				names.add(metaStr[i]);
			}
		StringBuilder out = new StringBuilder();
		for(int i = 0; i < names.size(); i++) {
			String s = names.get(i);
			if(logMINOR)
				Logger.minor(this, "name " + i + " = " + s);
			s = FileUtil.sanitize(s);
			if(logMINOR)
				Logger.minor(this, "Sanitized name " + i + " = " + s);
			if(s.length() > 0) {
				if(out.length() > 0)
					out.append('-');
				out.append(s);
			}
		}
		if(logMINOR)
			Logger.minor(this, "out = " + out.toString());
		if(out.length() == 0) {
			if(routingKey != null) {
				if(logMINOR)
					Logger.minor(this, "Returning base64 encoded routing key");
				return Base64.encode(routingKey);
			}
			// FIXME return null in this case, localise in a wrapper.
			return "unknown";
		}
		return out.toString();
	}

	/** Returns a <b>new</b> FreenetURI with a new suggested edition number.
	 * Note that the suggested edition number is only valid for USKs. */
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

	/** Returns a <b>new</b> FreenetURI with a new key type. Usually this
	 * will be invalid!
	 */
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

	/** Returns a <b>new</b> FreenetURI with a new routing key. KSKs do not
	 * have a routing key. */
	public FreenetURI setRoutingKey(byte[] newRoutingKey) {
		return new FreenetURI(
			keyType,
			docName,
			metaStr,
			newRoutingKey,
			cryptoKey,
			extra,
			suggestedEdition);
	}

	/** Throw an InsertException if we have any meta-strings. They are not
	 * valid for inserts, you must insert a directory to create a directory
	 * structure. */
	public void checkInsertURI() throws InsertException {
		if(metaStr != null && metaStr.length > 0)
			throw new InsertException(InsertException.META_STRINGS_NOT_SUPPORTED, this);
	}

	/** Throw an InsertException if the argument has any meta-strings. They
	 * are not valid for inserts, you must insert a directory to create a
	 * directory structure. */
	public static void checkInsertURI(FreenetURI uri) throws InsertException {
		uri.checkInsertURI();
	}

	/** Convert to a relative URI in the form of a URI (/KSK@gpl.txt etc). */
	public URI toRelativeURI() throws URISyntaxException {
		// Single-argument constructor used because it preserves encoded /'es in path.
		// Hence we can have slashes, question marks etc in the path, but they are encoded.
		return new URI('/' + toString(false, false));
	}

	/** Convert to a relative URI in the form of a URI, with the base path
	 * not necessarily /. */
	public URI toURI(String basePath) throws URISyntaxException {
		return new URI(basePath + toString(false, false));
	}

	/** Is this key an SSK? */
	public boolean isSSK() {
		return "SSK".equals(keyType);
	}

	/** Remove from the database. */
	public void removeFrom(ObjectContainer container) {
		// All members are inline (arrays, ints etc), treated as values, so we can happily just call delete(this).
		container.delete(this);
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(this == FreenetURI.EMPTY_CHK_URI) {
			throw new RuntimeException("Storing static CHK@ to database - can't remove it!");
		}
		return true;
	}

	public boolean objectCanUpdate(ObjectContainer container) {
		if(!container.ext().isActive(this)) {
			Logger.error(this, "Updating but not active!", new Exception("error"));
			return false;
		}
		return true;
	}

	public void objectOnDelete(ObjectContainer container) {
		if(logDEBUG) Logger.debug(this, "Deleting URI", new Exception("debug"));
	}

	/** Is this key a USK? */
	public boolean isUSK() {
		return "USK".equals(keyType);
	}

	/** Is this key a CHK? */
	public boolean isCHK() {
		return "CHK".equals(keyType);
	}

	/** Is this key a KSK? */
	public boolean isKSK() {
		return "KSK".equals(keyType);
	}

	/** Convert a USK into an SSK by appending "-" and the suggested edition
	 * to the document name and changing the key type. */
	public FreenetURI sskForUSK() {
		if(!keyType.equalsIgnoreCase("USK")) throw new IllegalStateException();
		return new FreenetURI("SSK", docName+"-"+suggestedEdition, metaStr, routingKey, cryptoKey, extra, 0);
	}

	private static final Pattern docNameWithEditionPattern;
	static {
		docNameWithEditionPattern = Pattern.compile(".*\\-([0-9]+)");
	}

	/** Could this SSK be the result of sskForUSK()? */
	public boolean isSSKForUSK() {
		return keyType.equalsIgnoreCase("SSK") && docNameWithEditionPattern.matcher(docName).matches();
	}

	/** Convert an SSK into a USK, if possible. */
	public FreenetURI uskForSSK() {
		if(!keyType.equalsIgnoreCase("SSK")) throw new IllegalStateException();
		Matcher matcher = docNameWithEditionPattern.matcher(docName);
		if (!matcher.matches())
			throw new IllegalStateException();

		int offset = matcher.start(1) - 1;
		String siteName = docName.substring(0, offset);
		long edition = Long.valueOf(docName.substring(offset + 1, docName.length()));

		return new FreenetURI("USK", siteName, metaStr, routingKey, cryptoKey, extra, edition);
	}

	/**
	 * Get the edition number, if the key is a USK or a USK converted to an
	 * SSK.
	 */
	public long getEdition() {
		if(keyType.equalsIgnoreCase("USK"))
			return suggestedEdition;
		else if(keyType.equalsIgnoreCase("SSK")) {
			Matcher matcher = docNameWithEditionPattern.matcher(docName);
			if (!matcher.matches()) /* Taken from uskForSSK, also modify there if necessary; TODO just use isSSKForUSK() here?! */
				throw new IllegalStateException();

			return Long.valueOf(docName.substring(matcher.start(1), docName.length()));
		} else
			throw new IllegalStateException();
	}

	@Override
	/** This looks expensive, but 99% of the time it will quit out pretty 
	 * early on: Either a different key type or a different routing key. The 
	 * worst case cost is relatively bad though. Unfortunately we can't use
	 * a HashMap if an attacker might be able to influence the keys and 
	 * create a hash collision DoS, so we *do* need this. */
	public int compareTo(FreenetURI o) {
		if(this == o) return 0;
		int cmp = keyType.compareTo(o.keyType);
		if(cmp != 0) return cmp;
		if(routingKey != null) {
			// Same type will have same routingKey != null
			cmp = Fields.compareBytes(routingKey, o.routingKey);
			if(cmp != 0) return cmp;
		}
		if(cryptoKey != null) {
			// Same type will have same cryptoKey != null
			cmp = Fields.compareBytes(cryptoKey, o.cryptoKey);
			if(cmp != 0) return cmp;
		}
		if(docName == null && o.docName != null) return -1;
		if(docName != null && o.docName == null) return 1;
		if(docName != null && o.docName != null) {
			cmp = docName.compareTo(o.docName);
			if(cmp != 0) return cmp;
		}
		if(extra != null) {
			// Same type will have same cryptoKey != null
			cmp = Fields.compareBytes(extra, o.extra);
			if(cmp != 0) return cmp;
		}
		if(metaStr != null && o.metaStr == null) return 1;
		if(metaStr == null && o.metaStr != null) return -1;
		if(metaStr != null && o.metaStr != null) {
			if(metaStr.length > o.metaStr.length) return 1;
			if(metaStr.length < o.metaStr.length) return -1;
			for(int i=0;i<metaStr.length;i++) {
				cmp = metaStr[i].compareTo(o.metaStr[i]);
				if(cmp != 0) return cmp;
			}
		}
		if(suggestedEdition > o.suggestedEdition) return 1;
		if(suggestedEdition < o.suggestedEdition) return -1;
		return 0;
	}
	
	public static final Comparator<FreenetURI> FAST_COMPARATOR = new Comparator<FreenetURI>() {

		@Override
		public int compare(FreenetURI uri0, FreenetURI uri1) {
			// Unfortunately the hashCode's may not have been computed yet.
			// But it's still cheaper to recompute them in the long run.
			int hash0 = uri0.hashCode();
			int hash1 = uri1.hashCode();
			if(hash0 > hash1) return 1;
			else if(hash1 > hash0) return -1;
			return uri0.compareTo(uri1);
		}
		
	};

	// TODO add something like the following?
	// public boolean isUpdatable() { return isUSK() || isSSKForUSK() }
}
