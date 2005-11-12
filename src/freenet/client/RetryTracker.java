package freenet.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.support.Logger;

/**
 * Keeps a list of SplitfileBlocks for each retry level.
 */
public class RetryTracker {

	class Level {
		final int level;
		final Vector blocks;

		Level(int l) {
			level = l;
			blocks = new Vector();
		}
		
		/**
		 * Return a random block.
		 * Call synchronized on RetryTracker.
		 */
		SplitfileBlock getBlock() {
			int len = blocks.size();
			int x = random.nextInt(len);
			SplitfileBlock block = (SplitfileBlock) blocks.remove(x);
			if(blocks.isEmpty())
				removeLevel(level);
			return block;
		}
		
		void add(SplitfileBlock block) {
			blocks.add(block);
		}
		
		/**
		 * Remove a specific block.
		 * Remove self if run out of blocks.
		 * Call synchronized on RetryTracker.
		 */
		void remove(SplitfileBlock block) {
			blocks.remove(block);
			if(blocks.isEmpty())
				removeLevel(level);
		}
	}

	final FailureCodeTracker fatalErrors;
	final FailureCodeTracker nonfatalErrors;
	final HashMap levels;
	final RandomSource random;
	final int maxLevel;
	final HashSet failedBlocksTooManyRetries;
	final HashSet failedBlocksFatalErrors;
	final HashSet runningBlocks;
	final HashSet succeededBlocks;
	private int curMaxLevel;
	private int curMinLevel;
	/** Maximum number of concurrently running threads */
	final int maxThreads;
	/** After we have successes on this many blocks, we should terminate, 
	 * even if there are threads running and blocks queued. */
	final int targetSuccesses;
	final boolean killOnFatalError;
	private boolean killed;
	private boolean finishOnEmpty;
	private final RetryTrackerCallback callback;

	/**
	 * Create a RetryTracker.
	 * @param maxLevel The maximum number of tries for each block.
	 * @param random The random number source.
	 * @param maxThreads The maximum number of threads to use.
	 * @param killOnFatalError Whether to terminate the tracker when a fatal
	 * error occurs on a single block.
	 * @param cb The callback to call .finish(...) when we no longer have
	 * anything to do *and* the client has set the finish on empty flag.
	 */
	public RetryTracker(int maxLevel, int targetSuccesses, RandomSource random, int maxThreads, boolean killOnFatalError, RetryTrackerCallback cb) {
		levels = new HashMap();
		fatalErrors = new FailureCodeTracker();
		nonfatalErrors = new FailureCodeTracker();
		this.targetSuccesses = targetSuccesses;
		this.maxLevel = maxLevel;
		this.random = random;
		curMaxLevel = curMinLevel = 0;
		failedBlocksTooManyRetries = new HashSet();
		failedBlocksFatalErrors = new HashSet();
		runningBlocks = new HashSet();
		succeededBlocks = new HashSet();
		this.maxThreads = maxThreads;
		this.killOnFatalError = killOnFatalError;
		this.finishOnEmpty = false;
		this.callback = cb;
	}

	/**
	 * Set the finish-on-empty flag to true.
	 * This means that when there are no longer any blocks to process, and there
	 * are none running, the tracker will call the client's finish(...) method.
	 */
	public synchronized void setFinishOnEmpty() {
		finishOnEmpty = true;
	}
	
	/** Remove a level */
	private synchronized void removeLevel(int level) {
		Integer x = new Integer(level);
		levels.remove(x);
		if(curMinLevel == level) {
			for(int i=curMinLevel;i<=curMaxLevel;i++) {
				x = new Integer(i);
				if(levels.get(x) != null) {
					curMinLevel = i;
					return;
				}
			}
			curMinLevel = curMaxLevel = 0;
			return;
		}
		if(curMaxLevel == level) {
			for(int i=curMaxLevel;i>=curMinLevel;i--) {
				x = new Integer(i);
				if(levels.get(x) != null) {
					curMaxLevel = i;
					return;
				}
			}
			curMinLevel = curMaxLevel = 0;
			return;
		}
	}

	/** Add a level */
	private synchronized Level addLevel(int level, Integer x) {
		if(level < 0) throw new IllegalArgumentException();
		Level l = new Level(level);
		levels.put(x, l);
		if(level > curMaxLevel) curMaxLevel = level;
		if(level < curMinLevel) curMinLevel = level;
		return l;
	}
	
	/** Get an existing level, or add one if necessary */
	private synchronized Level makeLevel(int level) {
		Integer x = new Integer(level);
		Level l = (Level) levels.get(x);
		if(l == null) {
			return addLevel(level, x);
		}
		else return l;
	}
	
	/**
	 * Add a block at retry level zero.
	 */
	public synchronized void addBlock(SplitfileBlock block) {
		if(killed) return;
		Level l = makeLevel(0);
		l.add(block);
		maybeStart(true);
	}
	
	/**
	 * A block got a nonfatal error and should be retried.
	 * Move it out of the running list and back into the relevant list, unless
	 * we have run out of retries.
	 */
	public synchronized void nonfatalError(SplitfileBlock block, int reasonCode) {
		nonfatalErrors.inc(reasonCode);
		runningBlocks.remove(block);
		int levelNumber = block.getRetryCount();
		levelNumber++;
		if(levelNumber > maxLevel) {
			failedBlocksTooManyRetries.add(block);
			Logger.minor(this, "Finished with "+block);
		} else {
			Level newLevel = makeLevel(levelNumber);
			newLevel.add(block);
		}
		maybeStart(false);
	}
	
	/**
	 * A block got a fatal error and should not be retried.
	 * Move it into the fatal error list.
	 * @param reasonCode A client-specific code indicating the type of failure.
	 */
	public synchronized void fatalError(SplitfileBlock block, int reasonCode) {
		fatalErrors.inc(reasonCode);
		runningBlocks.remove(block);
		failedBlocksFatalErrors.add(block);
		maybeStart(false);
	}

	/**
	 * If we can start some blocks, start some blocks.
	 * Otherwise if we are finished, call the callback's finish method.
	 */
	public synchronized void maybeStart(boolean cantCallFinished) {
		if(killed) return;
		Logger.minor(this, "succeeded: "+succeededBlocks.size()+", target: "+targetSuccesses+
				", running: "+runningBlocks.size()+", levels: "+levels.size()+", finishOnEmpty: "+finishOnEmpty);
		if(runningBlocks.size() == 1)
			Logger.minor(this, "Only block running: "+runningBlocks.toArray()[0]);
		else if(levels.isEmpty()) {
			for(Iterator i=runningBlocks.iterator();i.hasNext();) {
				Logger.minor(this, "Still running: "+i.next());
			}
		}
		if((succeededBlocks.size() >= targetSuccesses)
				|| (runningBlocks.isEmpty() && levels.isEmpty() && finishOnEmpty)) {
			killed = true;
			Logger.minor(this, "Finishing");
			SplitfileBlock[] running = runningBlocks();
			for(int i=0;i<running.length;i++) {
				running[i].kill();
			}
			if(!cantCallFinished)
				callback.finished(succeededBlocks(), failedBlocks(), fatalErrorBlocks());
			else {
				Runnable r = new Runnable() { public void run() { callback.finished(succeededBlocks(), failedBlocks(), fatalErrorBlocks()); } };
				Thread t = new Thread(r);
				t.setDaemon(true);
				t.start();
			}
		} else {
			while(runningBlocks.size() < maxThreads) {
				SplitfileBlock block = getBlock();
				if(block == null) break;
				Logger.minor(this, "Starting: "+block);
				block.start();
				runningBlocks.add(block);
			}
		}
	}

	public synchronized void success(SplitfileBlock block) {
		if(killed) return;
		runningBlocks.remove(block);
		succeededBlocks.add(block);
		maybeStart(false);
	}
	
	/**
	 * Get the next block to try. This is a randomly selected block from the
	 * lowest priority currently available. Move it into the running list.
	 */
	public synchronized SplitfileBlock getBlock() {
		if(killed) return null;
		Level l = (Level) levels.get(new Integer(curMinLevel));
		if(l == null) {
			if(!levels.isEmpty()) {
				Integer x = (Integer) levels.keySet().toArray()[0];
				Logger.error(this, "Inconsistent: min level = "+curMinLevel+", max level = "+curMaxLevel+" but level exists: "+x, new Exception("error"));
				curMinLevel = x.intValue();
				curMaxLevel = x.intValue();
				return getBlock();
			}
			return null;
		}
		return l.getBlock();
	}
	
	/**
	 * Get all running blocks.
	 */
	public synchronized SplitfileBlock[] runningBlocks() {
		return (SplitfileBlock[]) 
			runningBlocks.toArray(new SplitfileBlock[runningBlocks.size()]);
	}
	
	/**
	 * Get all blocks with fatal errors.
	 * SplitfileBlock's are assumed to remember their errors, so we don't.
	 */
	public synchronized SplitfileBlock[] fatalErrorBlocks() {
		return (SplitfileBlock[])
			failedBlocksFatalErrors.toArray(new SplitfileBlock[failedBlocksFatalErrors.size()]);
	}
	
	/**
	 * Get all blocks which didn't succeed in the maximum number of tries.
	 */
	public synchronized SplitfileBlock[] failedBlocks() {
		return (SplitfileBlock[])
		failedBlocksTooManyRetries.toArray(new SplitfileBlock[failedBlocksTooManyRetries.size()]);
	}
	
	/**
	 * Get all successfully downloaded blocks.
	 */
	public synchronized SplitfileBlock[] succeededBlocks() {
		return (SplitfileBlock[])
			succeededBlocks.toArray(new SplitfileBlock[succeededBlocks.size()]);
	}

	/**
	 * Count the number of blocks which could not be fetched because we ran out
	 * of retries.
	 */
	public synchronized int countFailedBlocks() {
		return failedBlocksTooManyRetries.size();
	}
	
	/**
	 * Highest number of completed retries of any block so far.
	 */
	public synchronized int highestRetries() {
		return curMaxLevel;
	}
	
	/**
	 * Lowest number of completed retries of any block so far.
	 */
	public synchronized int lowestRetries() {
		return curMinLevel;
	}
	
	/**
	 * Are there more blocks to process?
	 */
	public synchronized boolean moreBlocks() {
		return !levels.isEmpty();
	}

	public FailureCodeTracker getAccumulatedFatalErrorCodes() {
		return fatalErrors;
	}
	
	public FailureCodeTracker getAccumulatedNonFatalErrorCodes() {
		return nonfatalErrors;
	}

	public synchronized void kill() {
		killed = true;
		levels.clear();
		for(Iterator i=runningBlocks.iterator();i.hasNext();) {
			SplitfileBlock sb = (SplitfileBlock) i.next();
			sb.kill();
		}
		runningBlocks.clear();
	}
}
