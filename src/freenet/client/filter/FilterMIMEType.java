/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

/**
 * A MIME type, for purposes of the filter. There is one instance of this class for every MIME 
 * type that we have a filter for or that we have some knowledge of. Hence we can either filter the
 * content or generate a localised warning page with specific information such as the name of the
 * format and what the likely threats are.
 * 
 * @see freenet.client.MediaType MediaType represents an individual parsed MIME type string, e.g.
 * "text/plain; charset=ISO-8859-1". 
 */
public class FilterMIMEType {
	
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

	// Detail. Not necessarily an exhaustive list.
	
	public final boolean dangerousLinks;
	
	public final boolean dangerousInlines;
	
	public final boolean dangerousScripting;
	
	public final boolean dangerousReadMetadata;
	
	public final boolean dangerousWriteMetadata;
	
	public final boolean dangerousToWriteEvenWithFilter;
	
	// These are in addition to the above
	
	public final String readDescription;
	
	public final boolean takesACharset;
	
	public final String defaultCharset;
	
	public final CharsetExtractor charsetExtractor;
	/** If true, if we cannot detect the charset from a definite declaration
	 * or BOM, we will use the charset passed in from the referring document.
	 * So far this is only used by CSS. */
	public final boolean useMaybeCharset;
	
	FilterMIMEType(String type, String ext, String[] extraTypes, String[] extraExts,
			boolean safeToRead, boolean safeToWrite, ContentDataFilter readFilter,
			boolean dangerousLinks, boolean dangerousInlines,
			boolean dangerousScripting, boolean dangerousReadMetadata, 
			boolean dangerousWriteMetadata, boolean dangerousToWriteEvenWithFilter, 
			String readDescription, boolean takesACharset, 
			String defaultCharset, CharsetExtractor charsetExtractor, boolean useMaybeCharset) {
		this.primaryMimeType = type;
		this.primaryExtension = ext;
		this.alternateMimeTypes = extraTypes;
		this.alternateExtensions = extraExts;
		this.safeToRead = safeToRead;
		this.safeToWrite = safeToWrite;
		this.readFilter = readFilter;
		this.dangerousLinks = dangerousLinks;
		this.dangerousInlines = dangerousInlines;
		this.dangerousScripting = dangerousScripting;
		this.dangerousReadMetadata = dangerousReadMetadata;
		this.dangerousWriteMetadata = dangerousWriteMetadata;
		this.dangerousToWriteEvenWithFilter = dangerousToWriteEvenWithFilter;
		this.readDescription = readDescription;
		this.takesACharset = takesACharset;
		this.defaultCharset = defaultCharset;
		this.charsetExtractor = charsetExtractor;
		this.useMaybeCharset = useMaybeCharset;
	}

	/**
	 * Throw an exception indicating that this is a dangerous content type.
	 */
	public void throwUnsafeContentTypeException() throws KnownUnsafeContentTypeException {
		throw new KnownUnsafeContentTypeException(this);
	}
}
