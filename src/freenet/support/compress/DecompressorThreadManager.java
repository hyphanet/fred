/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import freenet.support.LogThresholdCallback;
import freenet.support.TimeUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Queue;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;

/** Creates and manages decompressor threads. This class is 
 * given all decompressors which should be applied to an
 * InputStream via addDecompressor. The decompressors will be
 * strung together and executed when the execute method is called.
 * This class also stores any errors which may arise.
 * @author sajack
*/
public class DecompressorThreadManager {

	final Queue<DecompressorThread> threads;
	PipedInputStream input;
	PipedOutputStream output = new PipedOutputStream();
	final long maxLen;
	private boolean finished = false;
	private Throwable error = null;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/** Creates a new DecompressorThreadManager
	 * @param inputStream The stream that will be decompressed, if compressed
	 * @param maxLen The maximum number of bytes to extract
	 */
	public DecompressorThreadManager(PipedInputStream inputStream, List<? extends Compressor> decompressors, long maxLen) throws IOException {
		threads = new ArrayDeque<DecompressorThread>(decompressors.size());
		this.maxLen = maxLen;
		if(inputStream == null) {
			IOException e = new IOException("Input stream may not be null");
			onFailure(e);
			throw e;
		}
		input = inputStream;
		while(!decompressors.isEmpty()) {
			Compressor compressor = decompressors.remove(decompressors.size()-1);
			if(logMINOR) Logger.minor(this, "Decompressing with "+compressor);
			DecompressorThread thread = new DecompressorThread(compressor, this, input, output, maxLen);
			threads.add(thread);
			input = new PipedInputStream(output);
			output = new PipedOutputStream();
		}
	}

	/** Creates and executes a new thread for each decompressor,
	 * chaining the output of the previous to the next.
	 * @return An InputStream from which uncompressed data may be read from
	 */
	public synchronized PipedInputStream execute() throws Throwable {
		if(error != null) throw error;
		if(threads.isEmpty()) {
			onFinish();
			return input;
		}
		try {
			int count = 0;
			while(!threads.isEmpty()){
				if(getError() != null) throw getError();
				DecompressorThread threadRunnable = threads.remove();
				if(threads.isEmpty()) threadRunnable.setLast();
				Thread t = new Thread(threadRunnable, "DecompressorThread"+count);
				t.start();
				if(logMINOR) Logger.minor(this, "Started decompressor thread "+t);
				count++;
			}
			output.close();
		} catch(Throwable t) {
			onFailure(t);
			throw t;
		} finally {
			Closer.close(output);
		}
		return input;
		
	}

	/** Informs the manager that a nonrecoverable exception has occured in the
	 * decompression threads
	 * @param e The thrown exception
	 */
	public synchronized void onFailure(Throwable t) {
		error = t;
		onFinish();
	}

	/** Marks that the decompression of the stream has finished and wakes
	 * threads blocking on completion */
	public synchronized void onFinish() {
		finished = true;
		notifyAll();
	}

	/** Blocks until all threads have finished executing and cleaning up.*/
	public synchronized void waitFinished() throws Throwable {
		long start = System.currentTimeMillis();
		while(!finished) {
			try {
				// FIXME remove the timeout here.
				// Something wierd is happening...
				//wait(0)
				wait(20*60*1000);
				long time = System.currentTimeMillis()-start;
				if(time > 20*60*1000)
					Logger.error(this, "Still waiting for decompressor chain after "+TimeUtil.formatTime(time));
			} catch(InterruptedException e) {
				//Do nothing
			}
		}
		if(error != null) throw error;
	}

	/** Returns an exception which was thrown during decompression
	 * @return Returns an exception which was caught during the decompression
	 */
	public synchronized Throwable getError() {
		return error;
	}

	/**Represents a thread which invokes a decompressor upon an
	 * input stream. These threads should be instantiated by a
	 * <code>DecompressorThreadManager</code>
	 * @author sajack
	 */
	class DecompressorThread implements Runnable {

		/**The compressor whose decompress method will be invoked*/
		final Compressor compressor;
		/**The stream compressed data will be read from*/
		private InputStream input;
		/**The stream decompressed data will be written*/
		private PipedOutputStream output;
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
		@Override
		public void run() {
			if(logMINOR) Logger.minor(this, "Decompressing...");
			try {
				if(manager.getError() == null) {
					compressor.decompress(input, output, maxLen, maxLen * 4);
					input.close();
					output.close();
					// Avoid relatively expensive repeated close on normal completion
					input = null;
					output = null;
					if(isLast) manager.onFinish();
				}
				if(logMINOR) Logger.minor(this, "Finished decompressing...");
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
}