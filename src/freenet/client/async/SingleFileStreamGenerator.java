/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.async;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**Writes a <code>Bucket</code> to an output stream.*/
public class SingleFileStreamGenerator implements StreamGenerator {

	final private Bucket bucket;

	SingleFileStreamGenerator(Bucket bucket) {
		this.bucket = bucket;
	}

	public void writeTo(OutputStream os, ObjectContainer container,
			ClientContext context) throws IOException {
		try{
			if(Logger.shouldLog(LogLevel.MINOR, this)) Logger.minor(this, "Generating Stream", new Exception("debug"));
			InputStream data = bucket.getInputStream();
			FileUtil.copy(data, os, -1);
			data.close();
			os.close();
			if(Logger.shouldLog(LogLevel.MINOR, this)) Logger.minor(this, "Stream completely generated", new Exception("debug"));
		} finally {
			Closer.close(bucket);
			Closer.close(os);
			bucket.free();
		}
	}

	public long size() {
		return bucket.size();
	}

}
