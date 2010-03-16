/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

/**
 * Stores the metadata that the client might actually be interested in.
 * Currently this is just the MIME type, but in future it might be more than
 * that. Size is not stored here, but maybe things like dublin core or 
 * whatever.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class ClientMetadata implements Cloneable {
	
	/** The document MIME type */
	private String mimeType;

	public ClientMetadata(){
		mimeType = null;
	}

	public ClientMetadata(String mime) {
		mimeType = (mime == null) ? null : mime.intern();
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
		if((mimeType == null) || "".equals(mimeType))
			mimeType = clientMetadata.mimeType;
	}

	/** Is there no MIME type? */
	public boolean isTrivial() {
		return ((mimeType == null) || "".equals(mimeType));
	}
	
	@Override
	public ClientMetadata clone() {
		try {
			return (ClientMetadata) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error(e);
		}
	}
	
	@Override
	public String toString() {
		return getMIMEType();
	}

	/** Clear the MIME type. */
	public void clear() {
		mimeType = null;
	}

	/** Return the MIME type minus any type parameters (e.g. charset, see 
	 * the RFCs defining the MIME type for details). */
	public String getMIMETypeNoParams() {
		String s = mimeType;
		if(s == null) return null;
		int i = s.indexOf(';');
		if(i > -1) {
			s = s.substring(i);
		}
		return s;
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
}
