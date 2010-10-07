/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;

import com.db4o.ObjectContainer;
import freenet.support.LogThresholdCallback;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;

/**Writes an array of <code>SplitFileFetcherSegment</code> objects to an output
 * stream.*/
public class SplitFileStreamGenerator implements StreamGenerator {

	private final SplitFileFetcherSegment[] segments;
	private final long length;
	private final int crossCheckBlocks;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	SplitFileStreamGenerator(SplitFileFetcherSegment[] segments, long length, int crossCheckBlocks) {
		this.segments = segments;
		this.length = length;
		this.crossCheckBlocks = crossCheckBlocks;
	}

	public void writeTo(OutputStream os, ObjectContainer container,
			ClientContext context) throws IOException {
		try {
			if(logMINOR) Logger.minor(this, "Generating Stream", new Exception("debug"));
			long bytesWritten = 0;
			for(SplitFileFetcherSegment segment : segments) {
				long max = (length < 0 ? 0 : (length - bytesWritten));
				bytesWritten += segment.writeDecodedDataTo(os, max, container);
				if(crossCheckBlocks == 0) segment.fetcherHalfFinished(container);
			}
			if(logMINOR) Logger.minor(this, "Stream completely generated", new Exception("debug"));
			os.close();
		} finally {
			Closer.close(os);
		}
	}

	public long size() {
		return length;
	}

}
