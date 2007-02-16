/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

/**
 * A ClientGetState.
 * Represents a stage in the fetch process.
 */
public interface ClientGetState {

	public void schedule();

	public void cancel();

	public long getToken();
}
