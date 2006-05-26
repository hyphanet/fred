package freenet.clients.http.filter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.HashMap;
import java.util.Hashtable;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Logger;

/**
 * Freenet content filter. This doesn't actually do any filtering,
 * it organizes everything and maintains the database.
 */
public class ContentFilter {

	static final Hashtable mimeTypesByName = new Hashtable();
	
	static {
		init();
	}
	
	public static void init() {
		// Register known MIME types
		
		// Plain text
		register(new MIMEType("text/plain", "txt", new String[0], new String[] { "text", "pot" },
				true, true, null, null, false, false, false, false, false, false, 
				"Plain text - not dangerous unless your browser is stupid (e.g. Internet Explorer)",
				"Plain text - not dangerous unless you include compromizing information",
				true, "US-ASCII", null));
		
		// GIF - probably safe - FIXME check this out, write filters 
		register(new MIMEType("image/gif", "gif", new String[0], new String[0], 
				true, false, null, null, false, false, false, false, false, false,
				"GIF image - probably not dangerous",
				"GIF image - probably not dangerous but you should wipe any comments",
				false, null, null));
		
		// JPEG - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/jpeg", "jpeg", new String[0], new String[] { "jpg" },
				true, false, null, null, false, false, false, false, false, false,
				"JPEG image - probably not dangerous",
				"JPEG image - probably not dangerous but can contain EXIF data", false, null, null));
		
		// PNG - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/png", "png", new String[0], new String[0],
				true, false, null, null, false, false, false, false, true, false,
				"PNG image - probably not dangerous",
				"PNG image - probably not dangerous but you should wipe any comments or text blocks",
				false, null, null));
		
		// ICO - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/x-icon", "ico", new String[] { "image/vnd.microsoft.icon", "image/ico", "application/ico"}, 
				new String[0], true, false, null, null, false, false, false, false, false, false,
				"Icon file - probably not dangerous",
				"Icon file - probably not dangerous (but can contain other data due to offset?)",
				false, null, null));
		
		// PDF - very dangerous - FIXME ideally we would have a filter, this is such a common format...
		register(new MIMEType("application/pdf", "pdf", new String[] { "application/x-pdf" }, new String[0],
				false, false, null, null, true, true, true, false, true, true,
				"Adobe(R) PDF document - VERY DANGEROUS!",
				"Adobe(R) PDF document - VERY DANGEROUS!",
				false, null, null));
		
		// HTML - dangerous if not filtered
		register(new MIMEType("text/html", "html", new String[] { "text/xhtml", "text/xml+xhtml" }, new String[] { "htm" },
				false, false /* maybe? */, new HTMLFilter(), null /* FIXME */, 
				true, true, true, true, true, true, "HTML - not dangerous if filtered",
				"HTML - may contain dangerous metadata etc; suggest you check it by hand",
				true, "iso-8859-1", new HTMLFilter()));
		
		// CSS - danagerous if not filtered, not sure about the filter
		register(new MIMEType("text/css", "css", new String[0], new String[0],
				false, false /* unknown */, new CSSReadFilter(), null,
				true, true, true, true, true, false,
				"CSS (cascading style sheet, usually used with HTML) - probably not dangerous if filtered, but the filter is not a whitelist filter so take care",
				"CSS (cascading style sheet, usually used with HTML) - this can probably contain metadata, check it by hand",
				true, "utf-8", new CSSReadFilter()));
		
	}
	
	public static void register(MIMEType mimeType) {
		synchronized(mimeTypesByName) {
			mimeTypesByName.put(mimeType.primaryMimeType, mimeType);
			String[] alt = mimeType.alternateMimeTypes;
			if(alt != null && alt.length > 0) {
				for(int i=0;i<alt.length;i++)
					mimeTypesByName.put(alt[i], mimeType);
			}
		}
	}

	public static MIMEType getMIMEType(String mimeType) {
		return (MIMEType) mimeTypesByName.get(mimeType);
	}

	/**
	 * Filter some data.
	 * @throws IOException If an internal error involving buckets occurred.
	 */
	public static Bucket filter(Bucket data, BucketFactory bf, String typeName, URI baseURI, FoundURICallback cb) throws UnsafeContentTypeException, IOException {
		String type = typeName;
		String options = "";
		String charset = null;
		HashMap otherParams = null;
		
		// First parse the MIME type
		
		int idx = type.indexOf(';');
		if(idx != -1) {
			options = type.substring(idx+1);
			type = type.substring(0, idx);
			// Parse options
			// Format: <type>/<subtype>[ optional white space ];[ optional white space ]<param>=<value>; <param2>=<value2>; ...
			String[] rawOpts = options.split(";");
			for(int i=0;i<rawOpts.length;i++) {
				String raw = rawOpts[i];
				idx = raw.indexOf('=');
				if(idx == -1) {
					Logger.error(ContentFilter.class, "idx = -1 for '=' on option: "+raw+" from "+typeName);
					continue;
				}
				String before = raw.substring(0, idx).trim();
				String after = raw.substring(idx+1).trim();
				if(before.equals("charset")) {
					charset = after;
				} else {
					if(otherParams == null) otherParams = new HashMap();
					otherParams.put(before, after);
				}
			}
		}
		
		// Now look for a MIMEType handler
		
		MIMEType handler = getMIMEType(type);
		
		if(handler == null)
			throw new UnknownContentTypeException(typeName);
		else {
			
			if(handler.safeToRead) {
				return data;
			}
			
			if(handler.readFilter != null) {
				if(handler.takesACharset && (charset == null || charset.length() == 0)) {
					charset = detectCharset(data, handler);
				}
				
				return handler.readFilter.readFilter(data, bf, charset, otherParams, new GenericReadFilterCallback(baseURI, cb));
			}
			handler.throwUnsafeContentTypeException();
			return null;
		}
	}

	private static String detectCharset(Bucket data, MIMEType handler) throws IOException {
		
		// Detect charset
		
		String charset = detectBOM(data);
		
		if(charset == null && handler.charsetExtractor != null) {

			// Obviously, this is slow!
			// This is why we need to detect on insert.
			
			if(handler.defaultCharset != null) {
				try {
					if((charset = handler.charsetExtractor.getCharset(data, handler.defaultCharset)) != null) {
						Logger.minor(ContentFilter.class, "Returning charset: "+charset);
						return charset;
					}
				} catch (DataFilterException e) {
					// Ignore
				}
			}
			try {
				if((charset = handler.charsetExtractor.getCharset(data, "ISO-8859-1")) != null)
					return charset;
			} catch (DataFilterException e) {
				// Ignore
			}
			try {
				if((charset = handler.charsetExtractor.getCharset(data, "UTF-8")) != null)
					return charset;
			} catch (DataFilterException e) {
				// Ignore
			}
			try {
				if((charset = handler.charsetExtractor.getCharset(data, "UTF-16")) != null)
					return charset;
			} catch (DataFilterException e) {
				// Ignore
			}
			try {
				if((charset = handler.charsetExtractor.getCharset(data, "UTF-32")) != null)
					return charset;
			} catch (UnsupportedEncodingException e) {
				// Doesn't seem to be supported by prior to 1.6.
				Logger.minor(ContentFilter.class, "UTF-32 not supported");
			} catch (DataFilterException e) {
				// Ignore
			}
			
		}
		
		// If it doesn't have a BOM, then it's *probably* safe to use as default.
		
		return handler.defaultCharset;
	}

	/**
	 * Detect a Byte Order Mark, a sequence of bytes which identifies a document as encoded with a 
	 * specific charset.
	 * @throws IOException 
	 */
	private static String detectBOM(Bucket bucket) throws IOException {
		byte[] data = new byte[5];
		InputStream is = bucket.getInputStream();
		int read = 0;
		while(read < data.length) {
			int x;
			try {
				x = is.read(data, read, data.length - read);
			} catch (EOFException e) {
				x = -1;
			}
			if(x <= 0) break;
		}
		is.close();
		if(startsWith(data, bom_utf8)) return "UTF-8";
		if(startsWith(data, bom_utf16_be) || startsWith(data, bom_utf16_le)) return "UTF-16";
		if(startsWith(data, bom_utf32_be) || startsWith(data, bom_utf32_le)) return "UTF-32";
		if(startsWith(data, bom_scsu)) return "SCSU";
		if(startsWith(data, bom_utf7_1) || startsWith(data, bom_utf7_2)
				|| startsWith(data, bom_utf7_3) || startsWith(data, bom_utf7_4)
				|| startsWith(data, bom_utf7_5)) return "UTF-7";
		if(startsWith(data, bom_utf_ebcdic)) return "UTF-EBCDIC";
		if(startsWith(data, bom_bocu_1)) return "BOCU-1";
		return null;
	}
	
	// Byte Order Mark's - from Wikipedia. We keep all of them because a rare encoding might
	// be deliberately used by an attacker to confuse the filter, because at present a charset
	// is not mandatory, and because some browsers may pick these up anyway even if one is present.
	
	static byte[] bom_utf8 = new byte[] { (byte)0xEF, (byte)0xBB, (byte)0xBF };
	static byte[] bom_utf16_be = new byte[] { (byte)0xFE, (byte)0xFF };
	static byte[] bom_utf16_le = new byte[] { (byte)0xFF, (byte)0xFE };
	static byte[] bom_utf32_be = new byte[] { (byte)0, (byte)0, (byte)0xFE, (byte)0xFF };
	static byte[] bom_utf32_le = new byte[] { (byte)0xFF, (byte)0xFE, (byte)0, (byte)0 };
	static byte[] bom_scsu = new byte[] { (byte)0x0E, (byte)0xFE, (byte)0xFF };
	static byte[] bom_utf7_1 = new byte[] { (byte)0x2B, (byte)0x2F, (byte)0x76, (byte) 0x38 };
	static byte[] bom_utf7_2 = new byte[] { (byte)0x2B, (byte)0x2F, (byte)0x76, (byte) 0x39 };
	static byte[] bom_utf7_3 = new byte[] { (byte)0x2B, (byte)0x2F, (byte)0x76, (byte) 0x2B };
	static byte[] bom_utf7_4 = new byte[] { (byte)0x2B, (byte)0x2F, (byte)0x76, (byte) 0x2F };
	static byte[] bom_utf7_5 = new byte[] { (byte)0x2B, (byte)0x2F, (byte)0x76, (byte) 0x38, (byte) 0x2D };
	static byte[] bom_utf_ebcdic = new byte[] { (byte)0xDD, (byte)0x73, (byte)0x66, (byte)0x73 };
	static byte[] bom_bocu_1 = new byte[] { (byte)0xFB, (byte)0xEE, (byte)0x28 };

	private static boolean startsWith(byte[] data, byte[] cmp) {
		for(int i=0;i<cmp.length;i++) {
			if(data[i] != cmp[i]) return false;
		}
		return true;
	}

}
