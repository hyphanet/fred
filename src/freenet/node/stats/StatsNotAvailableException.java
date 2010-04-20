/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.stats;

/**
 * @author nikotyan
 */
public class StatsNotAvailableException extends Exception {

	final private static long serialVersionUID = -7349859507599514672L;

	public StatsNotAvailableException() {
	}

	public StatsNotAvailableException(String s) {
		super(s);
	}

	public StatsNotAvailableException(String s, Throwable throwable) {
		super(s, throwable);
	}

	public StatsNotAvailableException(Throwable throwable) {
		super(throwable);
	}
}
