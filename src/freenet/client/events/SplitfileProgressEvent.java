/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import com.db4o.ObjectContainer;

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
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public int minSuccessfulBlocks;
	public final boolean finalizedTotal;
	
	public SplitfileProgressEvent(int totalBlocks, int succeedBlocks, int failedBlocks, 
			int fatallyFailedBlocks, int minSuccessfulBlocks, boolean finalizedTotal) {
		this.totalBlocks = totalBlocks;
		this.succeedBlocks = succeedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.minSuccessfulBlocks = minSuccessfulBlocks;
		this.finalizedTotal = finalizedTotal;
		if(logMINOR)
			Logger.minor(this, "Created SplitfileProgressEvent: total="+totalBlocks+" succeed="+succeedBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" min success="+minSuccessfulBlocks+" finalized="+finalizedTotal);
	}

	public String getDescription() {
		StringBuilder sb = new StringBuilder();
		sb.append("Completed ");
		if((minSuccessfulBlocks == 0) && (succeedBlocks == 0))
			minSuccessfulBlocks = 1;
		if(minSuccessfulBlocks == 0) {
			if(Logger.globalGetThreshold().ordinal() < LogLevel.MINOR.ordinal())
				Logger.error(this, "minSuccessfulBlocks=0, succeedBlocks="+succeedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal);
			else
				Logger.error(this, "minSuccessfulBlocks=0, succeedBlocks="+succeedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal, new Exception("debug"));
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
		sb.append(") ");
		sb.append(finalizedTotal ? " (finalized total)" : "");
		return sb.toString();
	}

	public int getCode() {
		return CODE;
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
