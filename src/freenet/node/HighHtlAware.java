/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Instances for objects which are aware of whether they are handling
 * requests with a high HTL. “High HTL” means at maximum HTL or close
 * to it.
 */
public interface HighHtlAware {

	/**
	 * @return whether the HTL is max HTL or max HTL minus 1 (usually 17 or 18).
	 */
	boolean isHighHtl();

}
