/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.util.LinkedList;
import java.util.concurrent.Semaphore;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

public class RealCompressor implements PrioRunnable {
	
	private final Executor exec;
	private ClientContext context;
	private static final LinkedList<CompressJob> _awaitingJobs = new LinkedList<CompressJob>();
	public static final Semaphore compressorSemaphore = new Semaphore(getMaxRunningCompressionThreads());

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public RealCompressor(Executor e) {
		this.exec = e;
	}
	
	public void setClientContext(ClientContext context) {
		this.context = context;
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
	
	public synchronized void enqueueNewJob(CompressJob j) {
		_awaitingJobs.add(j);
		if(logMINOR)
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
				compressorSemaphore.acquire(); 
			} catch(InterruptedException e) {
				Logger.error(this, "caught: "+e.getMessage(), e);
				continue;
			}
			
			final CompressJob finalJob = currentJob;
			exec.execute(new PrioRunnable() {
				public void run() {
					freenet.support.Logger.OSThread.logPID(this);
					try {
							try {
								finalJob.tryCompress(context);
							} catch(InsertException e) {
								finalJob.onFailure(e, null, context);
							} catch(OutOfMemoryError e) {
								OOMHandler.handleOOM(e);
								System.err.println("OffThreadCompressor thread above failed.");
								// Might not be heap, so try anyway
								finalJob.onFailure(new InsertException(InsertException.INTERNAL_ERROR, e, null), null, context);
							} catch(Throwable t) {
								Logger.error(this, "Caught in OffThreadCompressor: " + t, t);
								System.err.println("Caught in OffThreadCompressor: " + t);
								t.printStackTrace();
								// Try to fail gracefully
								finalJob.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), null, context);
							}

					} catch(Throwable t) {
						Logger.error(this, "Caught " + t + " in " + this, t);
					} finally {
						compressorSemaphore.release();
					}
				}

				public int getPriority() {
					return NativeThread.MIN_PRIORITY;
				}
			}, "Compressor thread for " + currentJob);
		}
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing RealCompressor in database", new Exception("error"));
		return false;
	}
	
	private static int getMaxRunningCompressionThreads() {
		int maxRunningThreads = 1;
		
		String osName = System.getProperty("os.name");
		if(osName.indexOf("Windows") == -1 && (osName.toLowerCase().indexOf("mac os x") > 0) || (!NativeThread.usingNativeCode()))
			// OS/X niceness is really weak, so we don't want any more background CPU load than necessary
			// Also, on non-Windows, we need the native threads library to be working.
			maxRunningThreads = 1;
		else {
			// Most other OSs will have reasonable niceness, so go by RAM.
			Runtime r = Runtime.getRuntime();
			int max = r.availableProcessors(); // FIXME this may change in a VM, poll it
			long maxMemory = r.maxMemory();
			if(maxMemory < 128 * 1024 * 1024)
				max = 1;
			else
				// one compressor thread per (128MB of ram + available core)
				max = Math.min(max, (int) (Math.min(Integer.MAX_VALUE, maxMemory / (128 * 1024 * 1024))));
			maxRunningThreads = max;
		}
		Logger.minor(RealCompressor.class, "Maximum Compressor threads: " + maxRunningThreads);
		return maxRunningThreads;
	}
}