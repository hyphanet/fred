/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

/**
 * Stores the metadata that the client might actually be interested in.
 */
public class ClientMetadata implements Cloneable {
	
	/** The document MIME type */
	private String mimeType;
	private COMPRESSOR_TYPE compressor;

	public ClientMetadata(){
		mimeType = null;
		compressor = null;
	}

	public ClientMetadata(String mime, COMPRESSOR_TYPE comp) {
		mimeType = (mime == null) ? null : mime.intern();
		compressor = comp;
	}
	
	/** Get the document MIME type. Will always be a valid MIME type, unless there
	 * has been an error; if it is unknown, will return application/octet-stream. */
	public String getMIMEType() {
		if((mimeType == null) || (mimeType.length() == 0))
			return DefaultMIMETypes.DEFAULT_MIME_TYPE;
		return mimeType;
	}

	/**
	 * Merge the given ClientMetadata, without overwriting our
	 * existing information.
	 */
	public void mergeNoOverwrite(ClientMetadata clientMetadata) {
		if((mimeType == null) || mimeType.equals(""))
			mimeType = clientMetadata.mimeType;
	}

	public boolean isTrivial() {
		return ((mimeType == null) || mimeType.equals(""));
	}
	
	@Override
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error(e);
		}
	}
	
	@Override
	public String toString() {
		return getMIMEType();
	}

	public void clear() {
		mimeType = null;
	}

	public String getMIMETypeNoParams() {
		String s = mimeType;
		if(s == null) return null;
		int i = s.indexOf(';');
		if(i > -1) {
			s = s.substring(i);
		}
		return s;
	}
	
	public COMPRESSOR_TYPE getCompressorType() {
		return compressor;
}
	
	public void setCompressorType(COMPRESSOR_TYPE compressor) {
		this.compressor = compressor;
	}
}
