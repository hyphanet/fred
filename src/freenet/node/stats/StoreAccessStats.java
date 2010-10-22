package freenet.node.stats;

public abstract class StoreAccessStats {
	
	public abstract long hits();
	
	public abstract long misses();
	
	public abstract long falsePos();
	
	public abstract long writes();
	
	public long readRequests() {
		return hits() + misses();
	}

	public long successfulReads() {
		if (readRequests() > 0)
			return hits();
		else
			return 0;
	}

	public double successRate() throws StatsNotAvailableException {
		if (readRequests() > 0)
			return (100.0 * hits() / readRequests());
		else
			throw new StatsNotAvailableException();
	}

	public double accessRate(long nodeUptimeSeconds) {
		return (1.0 * readRequests() / nodeUptimeSeconds);
	}

	public double writeRate(long nodeUptimeSeconds) {
		return (1.0 * writes() / nodeUptimeSeconds);
	}




}
