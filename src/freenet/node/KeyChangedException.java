/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Exception thrown when the primary key changes in the middle
 * of acquiring a packet number.
 */
public class KeyChangedException extends Exception {
	private static final long serialVersionUID = -1;
}
