package freenet.client.events;

import freenet.support.Logger;

public class SplitfileProgressEvent implements ClientEvent {

	public static int code = 0x07;
	
	public final int totalBlocks;
	public final int fetchedBlocks;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public int minSuccessfulBlocks;
	public final boolean finalizedTotal;
	
	public SplitfileProgressEvent(int totalBlocks, int fetchedBlocks, int failedBlocks, 
			int fatallyFailedBlocks, int minSuccessfulBlocks, boolean finalizedTotal) {
		this.totalBlocks = totalBlocks;
		this.fetchedBlocks = fetchedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.minSuccessfulBlocks = minSuccessfulBlocks;
		this.finalizedTotal = finalizedTotal;
	}

	public String getDescription() {
		StringBuffer sb = new StringBuffer();
		sb.append("Completed ");
		if(minSuccessfulBlocks == 0 && fetchedBlocks == 0)
			minSuccessfulBlocks = 1;
		if(minSuccessfulBlocks == 0) {
			if(Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "minSuccessfulBlocks=0, fetchedBlocks="+fetchedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal);
			else
				Logger.error(this, "minSuccessfulBlocks=0, fetchedBlocks="+fetchedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal, new Exception("debug"));
		} else {
			sb.append((100*(fetchedBlocks)/minSuccessfulBlocks));
			sb.append('%');
		}
		sb.append(' ');
		sb.append(fetchedBlocks);
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
		return code;
	}

}
