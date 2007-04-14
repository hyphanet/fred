/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.Native8Code;
import com.onionnetworks.fec.PureCode;

import freenet.support.LRUHashtable;
import freenet.support.Logger;

/**
 * FECCodec implementation using the onion code.
 */
public class StandardOnionFECCodec extends FECCodec {
	// REDFLAG: How big is one of these?
	private static int MAX_CACHED_CODECS = 8;
	
	static boolean noNative;

	private static final LRUHashtable recentlyUsedCodecs = new LRUHashtable();
	
	private static class MyKey {
		/** Number of input blocks */
		int k;
		/** Number of output blocks, including input blocks */
		int n;
		
		public MyKey(int n, int k) {
			this.n = n;
			this.k = k;
		}
		
		public boolean equals(Object o) {
			if(o instanceof MyKey) {
				MyKey key = (MyKey)o;
				return (key.n == n) && (key.k == k);
			} else return false;
		}
		
		public int hashCode() {
			return (n << 16) + k;
		}
	}

	public synchronized static FECCodec getInstance(int dataBlocks, int checkBlocks) {
		MyKey key = new MyKey(dataBlocks, checkBlocks + dataBlocks);
		StandardOnionFECCodec codec = (StandardOnionFECCodec) recentlyUsedCodecs.get(key);
		if(codec != null) {
			recentlyUsedCodecs.push(key, codec);
			return codec;
		}
		codec = new StandardOnionFECCodec(dataBlocks, checkBlocks + dataBlocks);
		recentlyUsedCodecs.push(key, codec);
		while(recentlyUsedCodecs.size() > MAX_CACHED_CODECS) {
			recentlyUsedCodecs.popKey();
		}
		return codec;
	}

	public StandardOnionFECCodec(int k, int n) {
		this.k = k;
		this.n = n;
		
		FECCode fec2 = null;
		if(!noNative) {
			try {
				fec2 = new Native8Code(k,n);
			} catch (Throwable t) {
				if(!noNative) {
					System.err.println("Failed to load native FEC: "+t);
					t.printStackTrace();
				}
				Logger.error(this, "Failed to load native FEC: "+t+" (k="+k+" n="+n+ ')', t);
				
				if(t instanceof UnsatisfiedLinkError)
					noNative = true;
			}
		}
		
		if (fec2 != null){
			fec = fec2;
		} else 	{
			fec = new PureCode(k,n);
		}

		// revert to below if above causes JVM crashes
		// Worst performance, but decode crashes
		// fec = new PureCode(k,n);
		// Crashes are caused by bugs which cause to use 320/128 etc. - n > 256, k < 256.

		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public int countCheckBlocks() {
		return n-k;
	}
	
	// ###############################
	
	/**
	 * The method used to submit {@link FECJob}s to the pool
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 * 
	 * @param FECJob
	 */
	public void addToQueue(FECJob job) {
		addToQueue(job, this);
	}
	
	public static void addToQueue(FECJob job, StandardOnionFECCodec codec){
		synchronized (_awaitingJobs) {
			if(fecRunnerThread == null) {
				if(fecRunnerThread != null) Logger.error(StandardOnionFECCodec.class, "The callback died!! restarting a new one, please report that error.");
				fecRunnerThread = new Thread(fecRunner, "FEC Pool");
				fecRunnerThread.setDaemon(true);
				fecRunnerThread.setPriority(Thread.MIN_PRIORITY);
				
				fecRunnerThread.start();
			}
			
			_awaitingJobs.addFirst(job);
		}
		if(logMINOR) Logger.minor(StandardOnionFECCodec.class, "Adding a new job to the queue (" +_awaitingJobs.size() + ").");
		synchronized (fecRunner){
			fecRunner.notifyAll();
		}
	}
	
	private static final LinkedList _awaitingJobs = new LinkedList();
	private static final FECRunner fecRunner = new FECRunner();
	private static Thread fecRunnerThread;
	
	/**
	 * An interface wich has to be implemented by FECJob submitters
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 * 
	 * WARNING: the callback is expected to release the thread !
	 */
	public interface StandardOnionFECCodecEncoderCallback{
		public void onEncodedSegment();
		public void onDecodedSegment();
	}
	
	/**
	 * A private Thread started by {@link StandardOnionFECCodec}...
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 *
	 *	TODO: maybe it ought to start more than one thread on SMP system ? (take care, it's memory consumpsive)
	 */
	private static class FECRunner implements Runnable {
		
		public void run(){
			try {
			while(true){
				FECJob job = null;
				try {
					// Get a job
					synchronized (_awaitingJobs) {
						job = (FECJob) _awaitingJobs.removeLast();
					}
				
					// Encode it
					try {
						if(job.isADecodingJob) {
							job.codec.realDecode(job.dataBlockStatus, job.checkBlockStatus, job.blockLength, job.bucketFactory);
						} else {
							job.codec.realEncode(job.dataBlocks, job.checkBlocks, job.blockLength, job.bucketFactory);
							// Update SplitFileBlocks from buckets if necessary
							if((job.dataBlockStatus != null) || (job.checkBlockStatus != null)){
								for(int i=0;i<job.dataBlocks.length;i++)
									job.dataBlockStatus[i].setData(job.dataBlocks[i]);
								for(int i=0;i<job.checkBlocks.length;i++)
									job.checkBlockStatus[i].setData(job.checkBlocks[i]);
							}
						}		
					} catch (IOException e) {
						Logger.error(this, "BOH! ioe:" + e.getMessage());
					}
					
					// Call the callback
					try {
						if(job.isADecodingJob)
							job.callback.onDecodedSegment();
						else
							job.callback.onEncodedSegment();
						
					} catch (Throwable e) {
						Logger.error(this, "The callback failed!" + e.getMessage(), e);
					}
				} catch (NoSuchElementException ne) {
					try {
						synchronized (this) {
							wait(Integer.MAX_VALUE);	
						}
					} catch (InterruptedException e) {}
				}
			}
			} finally { fecRunnerThread = null; }
		}
	}
}
