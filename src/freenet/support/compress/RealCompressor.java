/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import freenet.client.InsertException;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.io.NativeThread;
import java.util.LinkedList;

public class RealCompressor implements PrioRunnable {
	
	private final Executor exec;
	private static final LinkedList<CompressJob> _awaitingJobs = new LinkedList<CompressJob>();
	
	public RealCompressor(Executor e) {
		this.exec = e;
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
	
	public synchronized void enqueueNewJob(CompressJob j) {
		_awaitingJobs.add(j);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Enqueueing compression job: "+j);
		notifyAll();
	}

	public void run() {
		Logger.normal(this, "Starting RealCompressor");
		while(true) {
			CompressJob currentJob = null;
			try {
				synchronized(this) {
					currentJob = _awaitingJobs.poll();
					if(currentJob == null) {
						wait();
						continue;
					}
				}
				Compressor.COMPRESSOR_TYPE.compressorSemaphore.acquire(); 
			} catch(InterruptedException e) {
				Logger.error(this, "caught: "+e.getMessage(), e);
				continue;
			}
			
			final CompressJob finalJob = currentJob;
			exec.execute(new PrioRunnable() {
				public void run() {
					freenet.support.Logger.OSThread.logPID(this);
					try {
						while(true) {
							try {
								finalJob.tryCompress();
							} catch(InsertException e) {
								finalJob.onFailure(e, null);
							} catch(OutOfMemoryError e) {
								OOMHandler.handleOOM(e);
								System.err.println("OffThreadCompressor thread above failed.");
								// Might not be heap, so try anyway
								finalJob.onFailure(new InsertException(InsertException.INTERNAL_ERROR, e, null), null);
							} catch(Throwable t) {
								Logger.error(this, "Caught in OffThreadCompressor: " + t, t);
								System.err.println("Caught in OffThreadCompressor: " + t);
								t.printStackTrace();
								// Try to fail gracefully
								finalJob.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), null);
							}

						}
					} catch(Throwable t) {
						Logger.error(this, "Caught " + t + " in " + this, t);
					} finally {
						Compressor.COMPRESSOR_TYPE.compressorSemaphore.release();
					}
				}

				public int getPriority() {
					return NativeThread.MIN_PRIORITY;
				}
			}, "Compressor thread for " + currentJob);
		}
	}
}