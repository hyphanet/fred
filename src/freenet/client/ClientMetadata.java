/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

/**
 * Stores the metadata that the client might actually be interested in.
 * Currently this is just the MIME type, but in future it might be more than
 * that. Size is not stored here, but maybe things like dublin core or 
 * whatever.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads.
 */
public class ClientMetadata implements Cloneable, Serializable {
	
    private static final long serialVersionUID = 1L;
    /** The document MIME type */
	private String mimeType;

	public ClientMetadata(){
		mimeType = null;
	}

	public ClientMetadata(String mime) {
		mimeType = (mime == null) ? null : mime.intern();
	}
	
	private ClientMetadata(DataInputStream dis) throws MetadataParseException, IOException {
	    int magic = dis.readInt();
	    if(magic != MAGIC)
	        throw new MetadataParseException("Bad magic value in ClientMetadata");
	    short version = dis.readShort();
	    if(version != VERSION)
	        throw new MetadataParseException("Unrecognised version "+version+" in ClientMetadata");
	    boolean hasMIMEType = dis.readBoolean();
	    if(hasMIMEType)
	        mimeType = dis.readUTF();
	    else
	        mimeType = null;
	}
	
	/** Factory method to keep the API cleaner, avoid ambiguity; this won't be used as often as
	 * the String constructor. */
	public static ClientMetadata construct(DataInputStream dis) throws MetadataParseException, IOException {
	    return new ClientMetadata(dis);
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

	/** Is there no MIME type? */
	public boolean isTrivial() {
		return ((mimeType == null) || mimeType.equals(""));
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

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeShort(VERSION);
        if(mimeType == null)
            dos.writeBoolean(false);
        else {
            dos.writeBoolean(true);
            dos.writeUTF(mimeType);
        }
    }
    
    private static int VERSION = 1;
    private static int MAGIC = 0x021441fe8;

}
