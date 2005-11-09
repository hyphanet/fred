package freenet.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import freenet.crypt.RandomSource;

/**
 * Keeps a list of SplitfileBlocks for each retry level.
 */
public class RetryTracker {

	static class Level {
		final int level;
		final Vector blocks;
		final RetryTracker tracker;

		Level(RetryTracker tracker, int l) {
			level = l;
			this.tracker = tracker;
			blocks = new Vector();
		}
		
		/**
		 * Return a random block.
		 * Call synchronized on RetryTracker.
		 */
		SplitfileBlock getBlock() {
			int len = blocks.size();
			int x = tracker.random.nextInt(len);
			SplitfileBlock block = (SplitfileBlock) blocks.remove(x);
			if(blocks.isEmpty())
				tracker.removeLevel(level);
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
				tracker.removeLevel(level);
		}
	}

	final HashMap levels;
	final RandomSource random;
	final int maxLevel;
	final HashSet failedBlocksTooManyRetries;
	final HashSet failedBlocksFatalErrors;
	final HashSet runningBlocks;
	final HashSet succeededBlocks;
	private int curMaxLevel;
	private int curMinLevel;
	
	public RetryTracker(int maxLevel, RandomSource random) {
		levels = new HashMap();
		this.maxLevel = maxLevel;
		this.random = random;
		curMaxLevel = curMinLevel = 0;
		failedBlocksTooManyRetries = new HashSet();
		failedBlocksFatalErrors = new HashSet();
		runningBlocks = new HashSet();
		succeededBlocks = new HashSet();
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
		Level l = new Level(this, level);
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
		Level l = makeLevel(0);
		l.add(block);
	}
	
	/**
	 * A block got a nonfatal error and should be retried.
	 * Move it out of the running list and back into the relevant list, unless
	 * we have run out of retries.
	 */
	public synchronized void nonfatalError(SplitfileBlock block) {
		runningBlocks.remove(block);
		Level l = block.getLevel();
		if(l == null) throw new IllegalArgumentException();
		if(l.tracker != this) throw new IllegalArgumentException("Belongs to wrong tracker");
		int levelNumber = l.level;
		l.remove(block);
		levelNumber++;
		if(levelNumber > maxLevel) {
			failedBlocksTooManyRetries.add(block);
		} else {
			Level newLevel = makeLevel(levelNumber);
			newLevel.add(block);
		}
	}
	
	/**
	 * A block got a fatal error and should not be retried.
	 * Move it into the fatal error list.
	 */
	public synchronized void fatalError(SplitfileBlock block) {
		runningBlocks.remove(block);
		Level l = block.getLevel();
		if(l == null) throw new IllegalArgumentException();
		if(l.tracker != this) throw new IllegalArgumentException("Belongs to wrong tracker");
		l.remove(block);
		failedBlocksFatalErrors.add(block);
	}

	public synchronized void success(SplitfileBlock block) {
		runningBlocks.remove(block);
		succeededBlocks.add(block);
	}
	
	/**
	 * Get the next block to try. This is a randomly selected block from the
	 * lowest priority currently available. Move it into the running list.
	 */
	public synchronized SplitfileBlock getBlock() {
		Level l = (Level) levels.get(new Integer(curMinLevel));
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
	public synchronized SplitfileBlock[] errorBlocks() {
		return (SplitfileBlock[])
			failedBlocksFatalErrors.toArray(new SplitfileBlock[failedBlocksFatalErrors.size()]);
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
}
