/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Interface for something which counts bytes.
 */
public interface ByteCounter {
	
	public void sentBytes(int x);
	
	public void receivedBytes(int x);
	
	/** Sent payload - only include the number of bytes of actual payload i.e. data from the user's point of view, as opposed to overhead */
	public void sentPayload(int x);

}
