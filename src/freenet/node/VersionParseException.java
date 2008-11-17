/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * checked exception thrown by Version.getArbitraryBuildNumber()
 * @author toad
 */
public class VersionParseException extends Exception {
	private static final long serialVersionUID = -19006235321212642L;

	public VersionParseException(String msg) {
		super(msg);
	}

}
