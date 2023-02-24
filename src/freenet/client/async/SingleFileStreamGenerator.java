/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.async;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.LogThresholdCallback;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.FileUtil;

/**Writes a <code>Bucket</code> to an output stream.*/
public class SingleFileStreamGenerator implements StreamGenerator {

	final private Bucket bucket;
	final private boolean persistent;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	SingleFileStreamGenerator(Bucket bucket, boolean persistent) {
		this.bucket = bucket;
		this.persistent = persistent;
	}

	@Override
	public void writeTo(OutputStream os, ClientContext context) throws IOException {
		try (Bucket b = this.bucket) {
			if(logMINOR) {
				Logger.minor(this, "Generating Stream");
			}
			try (
				OutputStream out = os;
				InputStream data = b.getInputStream()
			) {
				FileUtil.copy(data, out, -1);
			}
			if(logMINOR) {
				Logger.minor(this, "Stream completely generated");
			}
		}
	}

	@Override
	public long size() {
		return bucket.size();
	}

}
