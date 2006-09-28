/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

/**
 * A MIME type, for purposes of the filter.
 */
public class MIMEType {
	
	public final String primaryMimeType;
	public final String[] alternateMimeTypes;
	
	public final String primaryExtension;
	public final String[] alternateExtensions;
	
	/** Is the data safe to read as-is? This is true for text/plain. */
	public final boolean safeToRead;
	
	/** Is the data safe to write as-is? */
	public final boolean safeToWrite;
	
	/** Content filter to make data safe to read */
	public final ContentDataFilter readFilter;
	
	/** Content filter to make data safe to write */
	public final ContentDataFilter writeFilter;

	// Detail. Not necessarily an exhaustive list.
	
	public final boolean dangerousLinks;
	
	public final boolean dangerousInlines;
	
	public final boolean dangerousScripting;
	
	public final boolean dangerousReadMetadata;
	
	public final boolean dangerousWriteMetadata;
	
	public final boolean dangerousToWriteEvenWithFilter;
	
	// These are in addition to the above
	
	public final String readDescription;
	
	public final String writeDescription;
	
	public final boolean takesACharset;
	
	public final String defaultCharset;
	
	public final CharsetExtractor charsetExtractor;
	
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

	/**
	 * Throw an exception indicating that this is a dangerous content type.
	 */
	public void throwUnsafeContentTypeException() throws KnownUnsafeContentTypeException {
		throw new KnownUnsafeContentTypeException(this);
	}
}
