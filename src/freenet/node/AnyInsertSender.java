/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

public interface AnyInsertSender {

	public abstract int getStatus();

	public abstract short getHTL();

	/**
	 * @return The current status as a string
	 */
	public abstract String getStatusString();

	public abstract boolean sentRequest();

	public abstract long getUID();

}