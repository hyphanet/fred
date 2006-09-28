/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

/**
 * Thrown when we need to restart a fetch process because of a problem
 * with an archive. This is usually because an archive has changed
 * since we last checked.
 */
public class ArchiveRestartException extends Exception {

	private static final long serialVersionUID = -7670838856130773012L;

	public ArchiveRestartException(String msg) {
		super(msg);
	}
}
