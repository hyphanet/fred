package freenet.client.events;

public class SplitfileProgressEvent implements ClientEvent {

	public static int code = 0x07;
	
	public final int totalBlocks;
	public final int fetchedBlocks;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public final int minSuccessfulBlocks;
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
		return "Completed "+(100*(fetchedBlocks)/minSuccessfulBlocks)+"% "+fetchedBlocks+"/"+minSuccessfulBlocks+" (failed "+failedBlocks+", fatally "+fatallyFailedBlocks+", total "+totalBlocks+")" +
			(finalizedTotal ? " (finalized total)" : "");
	}

	public int getCode() {
		return code;
	}

}
