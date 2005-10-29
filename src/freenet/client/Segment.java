package freenet.client;

import java.io.IOException;
import java.io.OutputStream;

import freenet.keys.FreenetURI;

/**
 * A segment, within a splitfile.
 */
public class Segment {

	final short splitfileType;
	final FreenetURI[] dataBlocks;
	final FreenetURI[] checkBlocks;
	
	/**
	 * Create a Segment.
	 * @param splitfileType The type of the splitfile.
	 * @param splitfileDataBlocks The data blocks to fetch.
	 * @param splitfileCheckBlocks The check blocks to fetch.
	 */
	public Segment(short splitfileType, FreenetURI[] splitfileDataBlocks, FreenetURI[] splitfileCheckBlocks) {
		this.splitfileType = splitfileType;
		dataBlocks = splitfileDataBlocks;
		checkBlocks = splitfileCheckBlocks;
	}

	/**
	 * Is the segment finished? (Either error or fetched and decoded)?
	 */
	public boolean isFinished() {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * If there was an error, throw it now.
	 */
	public void throwError() throws FetchException {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Return the length of the data, after decoding.
	 * Will throw unless known in advance, or  
	 * @return
	 */
	public long decodedLength() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Write the decoded data to the given output stream.
	 * Do not write more than the specified number of bytes (unless it is negative,
	 * in which case ignore it).
	 * @return The number of bytes written.
	 */
	public long writeDecodedDataTo(OutputStream os, long truncateLength) throws IOException {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Return true if the Segment has been started, otherwise false.
	 */
	public boolean isStarted() {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Start the Segment fetching the data. When it has finished fetching, it will
	 * notify the SplitFetcher.
	 */
	public void start(SplitFetcher fetcher, ArchiveContext actx, FetcherContext fctx, long maxTempLength) {
		// TODO Auto-generated method stub
		
	}
}
