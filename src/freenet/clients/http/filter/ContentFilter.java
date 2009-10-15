/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.HashMap;
import java.util.Hashtable;

import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;

/**
 * Freenet content filter. This doesn't actually do any filtering,
 * it organizes everything and maintains the database.
 */
public class ContentFilter {

	static final Hashtable<String, MIMEType> mimeTypesByName = new Hashtable<String, MIMEType>();
	
	static {
		init();
	}
	
	public static void init() {
		// Register known MIME types
		
		// Plain text
		register(new MIMEType("text/plain", "txt", new String[0], new String[] { "text", "pot" },
				true, true, null, null, false, false, false, false, false, false,
				l10n("textPlainReadAdvice"),
				l10n("textPlainWriteAdvice"),
				true, "US-ASCII", null, false));
		
		// GIF - has a filter 
		register(new MIMEType("image/gif", "gif", new String[0], new String[0], 
				true, false, new GIFFilter(), null, false, false, false, false, false, false,
				l10n("imageGifReadAdvice"),
				l10n("imageGifWriteAdvice"),
				false, null, null, false));
		
		// JPEG - has a filter
		register(new MIMEType("image/jpeg", "jpeg", new String[0], new String[] { "jpg" },
				true, false, new JPEGFilter(true, true), null, false, false, false, false, false, false,
				l10n("imageJpegReadAdvice"),
				l10n("imageJpegWriteAdvice"), false, null, null, false));
		
		// PNG - has a filter
		register(new MIMEType("image/png", "png", new String[0], new String[0],
				true, false, new PNGFilter(true, true, true), null, false, false, false, false, true, false,
				l10n("imagePngReadAdvice"),
				l10n("imagePngWriteAdvice"), false, null, null, false));


		// BMP - has a filter
		// Reference: http://filext.com/file-extension/BMP
		register(new MIMEType("image/bmp", "bmp", new String[] { "image/x-bmp","image/x-bitmap","image/x-xbitmap","image/x-win-bitmap","image/x-windows-bmp","image/ms-bmp","image/x-ms-bmp","application/bmp","application/x-bmp","application/x-win-bitmap"  }, new String[0],
				true, false, new BMPFilter(), null, false, false, false, false, true, false,
				l10n("imageBMPReadAdvice"),
				l10n("imageBMPWriteAdvice"), false, null, null, false));	


		
		// ICO - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/x-icon", "ico", new String[] { "image/vnd.microsoft.icon", "image/ico", "application/ico"}, 
				new String[0], true, false, null, null, false, false, false, false, false, false,
				l10n("imageIcoReadAdvice"),
				l10n("imageIcoWriteAdvice"), false, null, null, false));
		
		// PDF - very dangerous - FIXME ideally we would have a filter, this is such a common format...
		register(new MIMEType("application/pdf", "pdf", new String[] { "application/x-pdf" }, new String[0],
				false, false, null, null, true, true, true, false, true, true,
				l10n("applicationPdfReadAdvice"),
				l10n("applicationPdfWriteAdvice"),
				false, null, null, false));
		
		// HTML - dangerous if not filtered
		register(new MIMEType("text/html", "html", new String[] { "text/xhtml", "text/xml+xhtml", "application/xhtml+xml" }, new String[] { "htm" },
				false, false /* maybe? */, new HTMLFilter(), null /* FIXME */, 
				true, true, true, true, true, true, 
				l10n("textHtmlReadAdvice"),
				l10n("textHtmlWriteAdvice"),
				true, "iso-8859-1", new HTMLFilter(), false));
		
		// CSS - danagerous if not filtered, not sure about the filter
		register(new MIMEType("text/css", "css", new String[0], new String[0],
				false, false /* unknown */, new CSSReadFilter(), null,
				true, true, true, true, true, false,
				l10n("textCssReadAdvice"),
				l10n("textCssWriteAdvice"),
				true, "utf-8", null, true));
		
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("ContentFilter."+key);
	}

	public static void register(MIMEType mimeType) {
		synchronized(mimeTypesByName) {
			mimeTypesByName.put(mimeType.primaryMimeType, mimeType);
			String[] alt = mimeType.alternateMimeTypes;
			if((alt != null) && (alt.length > 0)) {
				for(int i=0;i<alt.length;i++)
					mimeTypesByName.put(alt[i], mimeType);
			}
		}
	}

	public static MIMEType getMIMEType(String mimeType) {
		return mimeTypesByName.get(mimeType);
	}

	public static class FilterOutput {
		public final Bucket data;
		public final String type;
		
		FilterOutput(Bucket data, String type) {
			this.data = data;
			this.type = type;
		}
	}

	/**
	 * Filter some data.
	 * 
	 * @param data
	 *            Input data
	 * @param bf
	 *            The bucket factory used to create the bucket to return the filtered data in.
	 * @param typeName
	 *            MIME type for input data
	 * @param maybeCharset 
	 * 			  MIME type of the referring document, as a hint, some types,
	 * 			  such as CSS, will inherit it if no other data is available.
	 * @throws IOException
	 *             If an internal error involving buckets occurred.
	 * @throws UnsafeContentTypeException
	 *             If the MIME type is declared unsafe (e.g. pdf files)
	 * @throws IllegalStateException
	 *             If data is invalid (e.g. corrupted file) and the filter have no way to recover.
	 */
	public static FilterOutput filter(Bucket data, BucketFactory bf, String typeName, URI baseURI, FoundURICallback cb, String maybeCharset) throws UnsafeContentTypeException, IOException {
		if(Logger.shouldLog(Logger.MINOR, ContentFilter.class))
			Logger.minor(ContentFilter.class, "filter(data.size="+data.size()+" typeName="+typeName);
		String type = typeName;
		String options = "";
		String charset = null;
		HashMap<String, String> otherParams = null;
		
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
					if (otherParams == null)
						otherParams = new HashMap<String, String>();
					otherParams.put(before, after);
				}
			}
		}
		
		// Now look for a MIMEType handler
		
		MIMEType handler = getMIMEType(type);
		
		if(handler == null)
			throw new UnknownContentTypeException(typeName);
		else {
			// Run the read filter if there is one.
			if(handler.readFilter != null) {
				if(handler.takesACharset && ((charset == null) || (charset.length() == 0))) {
					charset = detectCharset(data, handler, maybeCharset);
				}
				
				Bucket outputData = handler.readFilter.readFilter(data, bf, charset, otherParams, new GenericReadFilterCallback(baseURI, cb));
				if(charset != null)
					type = type + "; charset="+charset;
				return new FilterOutput(outputData, type);
			}
			
			if(handler.safeToRead) {
				return new FilterOutput(data, typeName);
			}
			
			handler.throwUnsafeContentTypeException();
			return null;
		}
	}

	private static String detectCharset(Bucket data, MIMEType handler, String maybeCharset) throws IOException {
		
		// Detect charset
		
		String charset = detectBOM(data);
		
		if((charset == null) && (handler.charsetExtractor != null)) {

			// Obviously, this is slow!
			// This is why we need to detect on insert.
			
			if(handler.defaultCharset != null) {
				try {
					if((charset = handler.charsetExtractor.getCharset(data, handler.defaultCharset)) != null) {
				        if(Logger.shouldLog(Logger.MINOR, ContentFilter.class))
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
		        if(Logger.shouldLog(Logger.MINOR, ContentFilter.class))
		        	Logger.minor(ContentFilter.class, "UTF-32 not supported");
			} catch (DataFilterException e) {
				// Ignore
			}
			
		}
		
		// If no BOM, use the charset from the referring document.
		if(handler.useMaybeCharset && maybeCharset != null && !maybeCharset.isEmpty())
			return maybeCharset;
		
		// If it doesn't have a BOM, then it's *probably* safe to use as default.
		
		return handler.defaultCharset;
	}

	/**
	 * Detect a Byte Order Mark, a sequence of bytes which identifies a document as encoded with a 
	 * specific charset.
	 * @throws IOException 
	 */
	private static String detectBOM(Bucket bucket) throws IOException {
		InputStream is = null;
		try {
			byte[] data = new byte[5];
			is = new BufferedInputStream(bucket.getInputStream());
			int read = 0;
			while(read < data.length) {
				int x;
				try {
					x = is.read(data, read, data.length - read);
				} catch(EOFException e) {
					x = -1;
				}
				if(x <= 0)
					break;
			}
			if(startsWith(data, bom_utf8))
				return "UTF-8";
			if(startsWith(data, bom_utf16_be))
				return "UTF-16BE";
			if(startsWith(data, bom_utf16_le))
				return "UTF-16LE";
			if(startsWith(data, bom_utf32_be))
				return "UTF-32BE";
			if(startsWith(data, bom_utf32_le))
				return "UTF-32LE";
			// We do NOT support UTF-32-2143 or UTF-32-3412
			// Java does not have charset support for them, and well,
			// very few people create web content on a PDP-11!
			
			if(startsWith(data, bom_utf32_2143))
				throw new UnsupportedCharsetInFilterException("UTF-32-2143");
			if(startsWith(data, bom_utf32_3412))
				throw new UnsupportedCharsetInFilterException("UTF-32-3412");
				
			if(startsWith(data, bom_scsu))
				return "SCSU";
			if(startsWith(data, bom_utf7_1) || startsWith(data, bom_utf7_2) || startsWith(data, bom_utf7_3) || startsWith(data, bom_utf7_4) || startsWith(data, bom_utf7_5))
				return "UTF-7";
			if(startsWith(data, bom_utf_ebcdic))
				return "UTF-EBCDIC";
			if(startsWith(data, bom_bocu_1))
				return "BOCU-1";
			
			is.close();
			
			return null;
		}
		finally {
			Closer.close(is);
		}
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
	
	// These BOMs are invalid. That is, we do not support them, they will produce an unrecoverable error, since we cannot decode them, but the browser might be able to, as e.g. the CSS spec refers to them.
	static byte[] bom_utf32_2143 = new byte[] { (byte)0x00, (byte)0x00, (byte)0xff, (byte)0xfe };
	static byte[] bom_utf32_3412 = new byte[] { (byte)0xfe, (byte)0xff, (byte)0x00, (byte)0x00 };

	private static boolean startsWith(byte[] data, byte[] cmp) {
		for(int i=0;i<cmp.length;i++) {
			if(data[i] != cmp[i]) return false;
		}
		return true;
	}

}
