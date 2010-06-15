/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedList;

import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.CountedInputStream;
import freenet.support.io.CountedOutputStream;

public class DecompressorThreadManager {

	final LinkedList<DecompressorThread> threads;
	InputStream input;
	PipedOutputStream output = new PipedOutputStream();
	final long maxLen;

	public DecompressorThreadManager(InputStream input, long maxLen) {
		threads = new LinkedList<DecompressorThread>();
		this.maxLen = maxLen;
		this.input = input;
	}

	public void addDecompressor(Compressor compressor)  throws IOException {
		threads.add(new DecompressorThread(compressor, input, output, maxLen));
		input = new PipedInputStream(output);
		output = new PipedOutputStream();
	}

	public InputStream execute() {
		while(!threads.isEmpty()){
			DecompressorThread thread = threads.remove();
			thread.start();
		}
		try {
			output.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			Closer.close(output);
		}
		return input;
	}
	/**
	 * Represents a thread which invokes a decompressor upon a
	 * retrieved request
	 * @author sajack
	 *
	 */
	class DecompressorThread extends Thread {

		final Compressor compressor;
		final InputStream input;
		final PipedOutputStream output;
		final long maxLen;

		public DecompressorThread(Compressor compressor, InputStream input, PipedOutputStream output, long maxLen) {
			this.compressor = compressor;
			this.input = input;
			this.output = output;
			this.maxLen = maxLen;
		}

		public void run() {
			try {
				CountedInputStream cin = new CountedInputStream(input);
				CountedOutputStream cout = new CountedOutputStream(output);
				compressor.decompress(cin, cout, maxLen, maxLen * 4);
				Logger.minor(this, "The decompressor read in "+cin.read()+" and decompressed "+cout.written());
				input.close();
				output.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (CompressionOutputSizeException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				Closer.close(input);
				Closer.close(output);
			}
		}

	}
}