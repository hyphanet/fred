/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedList;

import freenet.support.io.Closer;

public class DecompressorThreadManager {

	final LinkedList<DecompressorThread> threads;
	InputStream input;
	PipedOutputStream output = new PipedOutputStream();
	final long maxLen;
	private boolean finished = false;
	private Exception error = null;

	public DecompressorThreadManager(InputStream input, long maxLen) {
		threads = new LinkedList<DecompressorThread>();
		this.maxLen = maxLen;
		this.input = input;
	}

	public synchronized void addDecompressor(Compressor compressor)  throws IOException {
		DecompressorThread thread = new DecompressorThread(compressor, this, input, output, maxLen);
		threads.add(thread);
		input = new PipedInputStream(output);
		output = new PipedOutputStream();
	}

	public synchronized InputStream execute() {
		if(threads.isEmpty()) {
			onFinish();
			return input;
		}
		try {
			while(!threads.isEmpty()){
				DecompressorThread threadRunnable = threads.remove();
				new Thread(threadRunnable, "DecompressorThread").start();
			}
			output.close();			
		} catch(Exception e) {
			onFailure(e);
			onFinish();
		}
		finally {
			Closer.close(output);
		}
		return input;
		
	}

	public synchronized void onFailure(Exception e) {
		error = e;
	}

	public synchronized void onFinish() {
		finished = true;
		notifyAll();
	}

	public synchronized void waitFinished() {
		while(!finished) {
			try {
				wait();
			} catch(InterruptedException e) {
				//Do nothing
			}
		}
	}

	public synchronized Exception getError() {
		return error;
	}
}