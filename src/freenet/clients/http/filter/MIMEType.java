package freenet.clients.http.filter;

/**
 * A MIME type, for purposes of the filter.
 */
public class MIMEType {
	
	final String primaryMimeType;
	final String[] alternateMimeTypes;
	
	final String primaryExtension;
	final String[] alternateExtensions;
	
	/** Is the data safe to read as-is? This is true for text/plain. */
	final boolean safeToRead;
	
	/** Is the data safe to write as-is? */
	final boolean safeToWrite;
	
	/** Content filter to make data safe to read */
	final ContentDataFilter readFilter;
	
	/** Content filter to make data safe to write */
	final ContentDataFilter writeFilter;

	// Detail. Not necessarily an exhaustive list.
	
	final boolean dangerousLinks;
	
	final boolean dangerousInlines;
	
	final boolean dangerousScripting;
	
	final boolean dangerousReadMetadata;
	
	final boolean dangerousWriteMetadata;
	
	final boolean dangerousToWriteEvenWithFilter;
	
	// These are in addition to the above
	
	final String readDescription;
	
	final String writeDescription;
	
	final boolean takesACharset;
	
	final String defaultCharset;
	
	final CharsetExtractor charsetExtractor;
	
	MIMEType(String type, String ext, String[] extraTypes, String[] extraExts,
			boolean safeToRead, boolean safeToWrite, ContentDataFilter readFilter,
			ContentDataFilter writeFilter, boolean dangerousLinks, boolean dangerousInlines,
			boolean dangerousScripting, boolean dangerousReadMetadata, 
			boolean dangerousWriteMetadata, boolean dangerousToWriteEvenWithFilter, 
			String readDescription, String writeDescription, boolean takesACharset, 
			String defaultCharset, CharsetExtractor charsetExtractor) {
		this.primaryMimeType = type;
		this.primaryExtension = ext;
		this.alternateMimeTypes = extraTypes;
		this.alternateExtensions = extraExts;
		this.safeToRead = safeToRead;
		this.safeToWrite = safeToWrite;
		this.readFilter = readFilter;
		this.writeFilter = writeFilter;
		this.dangerousLinks = dangerousLinks;
		this.dangerousInlines = dangerousInlines;
		this.dangerousScripting = dangerousScripting;
		this.dangerousReadMetadata = dangerousReadMetadata;
		this.dangerousWriteMetadata = dangerousWriteMetadata;
		this.dangerousToWriteEvenWithFilter = dangerousToWriteEvenWithFilter;
		this.readDescription = readDescription;
		this.writeDescription = writeDescription;
		this.takesACharset = takesACharset;
		this.defaultCharset = defaultCharset;
		this.charsetExtractor = charsetExtractor;
	}
}
