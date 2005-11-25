package freenet.client.events;

public class SplitfileProgressEvent implements ClientEvent {

	public static int code = 0x07;
	
	public final int totalBlocks;
	public final int fetchedBlocks;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public final int runningBlocks;
	
	public SplitfileProgressEvent(int totalBlocks, int fetchedBlocks, int failedBlocks, 
			int fatallyFailedBlocks, int runningBlocks) {
		this.totalBlocks = totalBlocks;
		this.fetchedBlocks = fetchedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.runningBlocks = runningBlocks;
	}

	public String getDescription() {
		return "Completed "+(100*(fetchedBlocks+failedBlocks+fatallyFailedBlocks)/totalBlocks)+"% "+fetchedBlocks+"/"+totalBlocks+" (failed "+failedBlocks+", fatally "+fatallyFailedBlocks+", running "+runningBlocks+")";
	}

	public int getCode() {
		return code;
	}

}
