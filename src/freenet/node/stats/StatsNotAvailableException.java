package freenet.node.stats;

/**
 * User: nikotyan
 * Date: Apr 16, 2010
 */
public class StatsNotAvailableException extends Exception {
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
