/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

/**
 * Thrown when a persistent request cannot be parsed.
 */
public class PersistenceParseException extends Exception {
	private static final long serialVersionUID = -1;

	public PersistenceParseException(String string) {
		super(string);
	}

	public PersistenceParseException(String string, Exception reason) {
		super(string, reason);
	}

}
