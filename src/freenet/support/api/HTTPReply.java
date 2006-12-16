/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

/**
 * An HTTP response.
 */
public final class HTTPReply {

	private final String mimeType;
	private final Bucket data;
	
	public HTTPReply(String mimeType, Bucket data) {
		this.mimeType = mimeType;
		this.data = data;
	}
	
	public final String getMIMEType() {
		return mimeType;
	}
	
	public final Bucket getData() {
		return data;
	}

}
