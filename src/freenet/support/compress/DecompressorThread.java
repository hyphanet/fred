/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */

package freenet.support.compress;

import java.io.InputStream;
import java.io.PipedOutputStream;

import freenet.support.io.Closer;
import freenet.support.io.CountedInputStream;
import freenet.support.io.CountedOutputStream;

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
	
	public DecompressorThread(Compressor compressor, DecompressorThreadManager manager, InputStream input, PipedOutputStream output, long maxLen) {
		this.compressor = compressor;
		this.input = input;
		this.output = output;
		this.maxLen = maxLen;
		this.manager = manager;
	}

	public void run() {
		try {
			CountedInputStream cin = new CountedInputStream(input);
			CountedOutputStream cout = new CountedOutputStream(output);
			compressor.decompress(cin, cout, maxLen, maxLen * 4);
			input.close();
			output.close();
		} catch (Exception e) {
			manager.onFailure(e);
		} finally {
			Closer.close(input);
			Closer.close(output);
			manager.onFinish();
		}
	}
}