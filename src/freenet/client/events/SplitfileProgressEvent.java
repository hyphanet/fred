package freenet.client.events;

public class SplitfileProgressEvent implements ClientEvent {

	public static int code = 0x07;
	
	public final int totalBlocks;
	public final int fetchedBlocks;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public final int minSuccessfulBlocks;
	
	public SplitfileProgressEvent(int totalBlocks, int fetchedBlocks, int failedBlocks, 
			int fatallyFailedBlocks, int minSuccessfulBlocks) {
		this.totalBlocks = totalBlocks;
		this.fetchedBlocks = fetchedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.minSuccessfulBlocks = minSuccessfulBlocks;
	}

	public String getDescription() {
		return "Completed "+(100*(fetchedBlocks+failedBlocks+fatallyFailedBlocks)/minSuccessfulBlocks)+"% "+fetchedBlocks+"/"+totalBlocks+" (failed "+failedBlocks+", fatally "+fatallyFailedBlocks+", need "+minSuccessfulBlocks+", total "+totalBlocks+")";
	}

	public int getCode() {
		return code;
	}

}
