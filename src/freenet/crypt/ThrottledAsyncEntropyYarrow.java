/*
 * Created on Oct 27, 2003

 */
package freenet.crypt;

import freenet.support.BlockingQueue;

/**
 * @author Iakin
 * 
 * Exactly the same as Yarrow except that supplied entropy will be added asynchronously and that some
 * entropy additions might be ignored
 * 
 */
public class ThrottledAsyncEntropyYarrow extends Yarrow {
	long maxEntropyQueueSize;
	Thread entropyProcessor;
	BlockingQueue entropy;
	private class EntropyQueueItem {
		EntropySource source;
		long data;
		int entropyGuess;
		EntropyQueueItem(EntropySource source, long data, int entropyGuess) {
			this.source = source;
			this.data = data;
			this.entropyGuess = entropyGuess;
		}
	}
	public ThrottledAsyncEntropyYarrow(String seed, String digest, String cipher,boolean updateSeed,long maxEntropyQueueSize)
	{
		super(seed,digest,cipher,updateSeed);
		this.maxEntropyQueueSize = maxEntropyQueueSize;
		initialize();
	}

	public int acceptEntropy(EntropySource source, long data, int entropyGuess) {
		if(entropy.size() < maxEntropyQueueSize)
			entropy.enqueue(new EntropyQueueItem(source,data,entropyGuess));
		return 32; //TODO: What should we do here.. seem like no part of fred currently uses the retuned value /Iakin@2003-10-27
	}
	private void initialize() {
		//entropy = new BlockingQueue(); Done in readStartupEntropy below
		entropyProcessor = new Thread(new Runnable() {
			public void run() {
				while (true)
					try {
						EntropyQueueItem e = (EntropyQueueItem) entropy.dequeue();
						ThrottledAsyncEntropyYarrow.super.acceptEntropy(e.source, e.data, e.entropyGuess);
					} catch (InterruptedException e) {
					}
			}
		});
		entropyProcessor.setDaemon(true);
		entropyProcessor.setPriority(Thread.MIN_PRIORITY);
		entropyProcessor.setName("PRNG/Yarrow entropy processing thread");
		entropyProcessor.start();
	}

	protected void readStartupEntropy(EntropySource startupEntropy) {
		//This method is called during Yarrow:s initialization which is run before our own..
		//this is how I splice in the instanciation of the BlockingQueue and a temporary queuesize..
		//Quite ugly way of doing it but, well if someone knows a better way of doing it then
		//feel free...
		if(maxEntropyQueueSize == 0) 
			maxEntropyQueueSize = 100;
		entropy = new BlockingQueue();
		super.readStartupEntropy(startupEntropy);
	}

}
