/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.InputStream;
import java.io.PipedOutputStream;

import freenet.support.io.Closer;

/**Represents a thread which invokes a decompressor upon an
 * input stream. These threads should be instantiated by a
 * <code>DecompressorThreadManager</code>
 * @author sajack
 */
class DecompressorThread implements Runnable {

	/**The compressor whose decompress method will be invoked*/
	final Compressor compressor;
	/**The stream compressed data will be read from*/
	final InputStream input;
	/**The stream decompressed data will be written*/
	final PipedOutputStream output;
	/**A upper limit to how much data may be decompressed. This is passed to the decompressor*/
	final long maxLen;
	/**The manager which created the thread*/
	final DecompressorThreadManager manager;
	/**Whether or not this thread should signal the manager that decompression has finished*/
	boolean isLast = false;

	public DecompressorThread(Compressor compressor, DecompressorThreadManager manager, InputStream input, PipedOutputStream output, long maxLen) {
		this.compressor = compressor;
		this.input = input;
		this.output = output;
		this.maxLen = maxLen;
		this.manager = manager;
	}

	/**Begins the decompression */
	public void run() {
		try {
			if(manager.getError() == null) {
				compressor.decompress(input, output, maxLen, maxLen * 4);
				input.close();
				output.close();
				if(isLast) manager.onFinish();
			}
		} catch (Exception e) {
			manager.onFailure(e);
		} finally {
			Closer.close(input);
			Closer.close(output);
		}
	}

	/** Should be called before executing the thread when there 
	 * are no further decompressors pending*/
	public void setLast() {
		isLast = true;
	}
}