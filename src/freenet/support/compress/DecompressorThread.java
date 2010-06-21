/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */

package freenet.support.compress;

import java.io.InputStream;
import java.io.PipedOutputStream;

import freenet.support.io.Closer;

/**
 * Represents a thread which invokes a decompressor upon a
 * retrieved request
 * @author sajack
 *
 */
class DecompressorThread implements Runnable {

	final Compressor compressor;
	final InputStream input;
	final PipedOutputStream output;
	final long maxLen;
	final DecompressorThreadManager manager;
	boolean isLast = false;
	
	public DecompressorThread(Compressor compressor, DecompressorThreadManager manager, InputStream input, PipedOutputStream output, long maxLen) {
		this.compressor = compressor;
		this.input = input;
		this.output = output;
		this.maxLen = maxLen;
		this.manager = manager;
	}

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

	public void setLast() {
		isLast = true;
	}
}