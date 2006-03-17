package freenet.clients.http.filter;

import java.util.Hashtable;

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
				true, "iso-8859-1", null));
		
		// GIF - probably safe - FIXME check this out, write filters 
		register(new MIMEType("image/gif", "gif", new String[0], new String[0], 
				true, false, null, null, false, false, false, false, false, false,
				"GIF image - probably not dangerous",
				"GIF image - probably not dangerous but you should wipe any comments",
				false, null, null));
		
		// JPEG - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/jpeg", "jpeg", new String[0], new String[] { "jpg" },
				true, true, null, null, false, false, false, false, false, false,
				"JPEG image - probably not dangerous",
				"JPEG image - probably not dangerous", false, null, null));
		
		// PNG - probably safe - FIXME check this out, write filters
		register(new MIMEType("image/png", "png", new String[0], new String[0],
				true, false, null, null, false, false, false, false, true, false,
				"PNG image - probably not dangerous",
				"PNG image - probably not dangerous but you should wipe any comments or text blocks",
				false, null, null));
		
		// PDF - very dangerous - FIXME ideally we would have a filter, this is such a common format...
		register(new MIMEType("application/pdf", "pdf", new String[] { "application/x-pdf" }, new String[0],
				false, false, null, null, true, true, true, true, true, true,
				"Adobe(R) PDF document - VERY DANGEROUS!",
				"Adobe(R) PDF document - VERY DANGEROUS!",
				false, null, null));
		
		// HTML - dangerous if not filtered
		register(new MIMEType("text/html", "html", new String[] { "text/xhtml", "text/xml+xhtml" }, new String[] { "htm" },
				false, false /* maybe? */, new HTMLReadFilter(), new HTMLWriteFilter(), 
				true, true, true, true, true, true, false,
				"HTML - not dangerous if filtered",
				"HTML - may contain dangerous metadata etc; suggest you check it by hand",
				true, "iso-8859-1", new HTMLCharsetExtractor()));
		
		// CSS - danagerous if not filtered, not sure about the filter
		register(new MIMEType("text/css", "css", new String[0], new String[0],
				false, false /* unknown */, new CSSReadFilter(), null,
				true, true, true, true, true, false,
				"CSS (cascading style sheet, usually used with HTML) - probably not dangerous if filtered, but the filter is not a whitelist filter so take care",
				"CSS (cascading style sheet, usually used with HTML) - this can probably contain metadata, check it by hand",
				true, "iso-8859-1", new CSSCharsetExtractor()));
		
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
	
	public static 
	
}
