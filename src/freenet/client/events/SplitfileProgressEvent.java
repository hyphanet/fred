/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import java.util.Date;

import freenet.client.async.ClientRequester;
import freenet.support.CurrentTimeUTC;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class SplitfileProgressEvent implements ClientEvent {

    private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public final static int CODE = 0x07;
	
	public final int totalBlocks;
	public final int succeedBlocks;
	/** @see ClientRequester#latestSuccess */
	public final Date latestSuccess;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	/** @see ClientRequester#latestFailure */
	public final Date latestFailure;
	public final int minSuccessFetchBlocks;
	public int minSuccessfulBlocks;
	public final boolean finalizedTotal;
	
	public SplitfileProgressEvent(int totalBlocks, int succeedBlocks, Date latestSuccess, 
			int failedBlocks, int fatallyFailedBlocks, Date latestFailure, int minSuccessfulBlocks,
			int minSuccessFetchBlocks, boolean finalizedTotal) {
		this.totalBlocks = totalBlocks;
		this.succeedBlocks = succeedBlocks;
		// clone() because Date is mutable.
		this.latestSuccess = latestSuccess != null ? (Date)latestSuccess.clone() : null; 
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		// clone() because Date is mutable.
		this.latestFailure = latestFailure != null ? (Date)latestFailure.clone() : null;
		this.minSuccessfulBlocks = minSuccessfulBlocks;
		this.finalizedTotal = finalizedTotal;
		this.minSuccessFetchBlocks = minSuccessFetchBlocks;
		if(logMINOR)
			Logger.minor(this, "Created SplitfileProgressEvent: total="+totalBlocks+" succeed="+succeedBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" min success="+minSuccessfulBlocks+" finalized="+finalizedTotal);
	}
	
	protected SplitfileProgressEvent() {
	    // For serialization.
	    totalBlocks = 0;
	    succeedBlocks = 0;
	    // See ClientRequester.getLatestSuccess() for why this defaults to current time.
	    latestSuccess = CurrentTimeUTC.get();
	    failedBlocks = 0;
	    fatallyFailedBlocks = 0;
	    latestFailure = null;
	    minSuccessFetchBlocks = 0;
	    finalizedTotal = false;
	}

	/** TODO: Developer's tools: Include {@link #latestSuccess} and {@link #latestFailure}. */
	@Override
	public String getDescription() {
		StringBuilder sb = new StringBuilder();
		sb.append("Completed ");
		if((minSuccessfulBlocks == 0) && (succeedBlocks == 0))
			minSuccessfulBlocks = 1;
		if(minSuccessfulBlocks == 0) {
			if(LogLevel.MINOR.matchesThreshold(Logger.globalGetThresholdNew()))
				Logger.error(this, "minSuccessfulBlocks=0, succeedBlocks="+succeedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal, new Exception("debug"));
			else
				Logger.error(this, "minSuccessfulBlocks=0, succeedBlocks="+succeedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal);
		} else {
			sb.append((100*(succeedBlocks)/minSuccessfulBlocks));
			sb.append('%');
		}
		sb.append(' ');
		sb.append(succeedBlocks);
		sb.append('/');
		sb.append(minSuccessfulBlocks);
		sb.append(" (failed ");
		sb.append(failedBlocks);
		sb.append(", fatally ");
		sb.append(fatallyFailedBlocks);
		sb.append(", total ");
		sb.append(totalBlocks);
		sb.append(", minSuccessFetch ");
		sb.append(minSuccessFetchBlocks);
		sb.append(") ");
		sb.append(finalizedTotal ? " (finalized total)" : "");
		return sb.toString();
	}

	@Override
	public int getCode() {
		return CODE;
	}

}
