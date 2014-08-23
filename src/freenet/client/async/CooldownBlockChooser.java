package freenet.client.async;

import java.util.Random;

/** A block chooser including support for cooldown */
public class CooldownBlockChooser extends SimpleBlockChooser {

    public CooldownBlockChooser(int blocks, Random random, int maxRetries, int cooldownTries, 
            long cooldownTime) {
        super(blocks, random, maxRetries);
        this.cooldownTries = cooldownTries;
        this.cooldownTime = cooldownTime;
        blockCooldownTimes = new long[blocks];
    }

    /** Every cooldownTries attempts, a key will enter cooldown, and won't be re-tried for a period. */
    private final int cooldownTries;
    /** Cooldown lasts this long for each key. */
    private final long cooldownTime;
    /** Time at which the whole block chooser will next become fetchable. 0 to mean it is fetchable
     * now. Equal to the earliest valid cooldown time for any individual block. INVARIANT: This can 
     * safely be too early (small) but not too late (large). */
    private long overallCooldownTime;
    /** Time at which each block becomes fetchable again. 0 means it is fetchable now. */
    private long[] blockCooldownTimes;
    /** Current time, updated at the beginning of chooseKey(). */
    private long now;
    
    @Override
    public synchronized int chooseKey() {
        now = System.currentTimeMillis();
        if(overallCooldownTime > now) return -1;
        overallCooldownTime = Long.MAX_VALUE; // Will find the earliest wake-up.
        int ret = super.chooseKey();
        if(ret != -1) 
            overallCooldownTime = 0; // Fetchable now, else waiting for cooldown.
        return ret;
    }
    
    @Override
    protected boolean checkValid(int blockNo) {
        if(!super.checkValid(blockNo)) return false;
        long wakeUp = blockCooldownTimes[blockNo];
        if(now > wakeUp) {
            blockCooldownTimes[blockNo] = 0;
            return true;
        } else {
            // Update the overall cooldown wakeup time.
            overallCooldownTime = Math.min(overallCooldownTime, wakeUp);
            return false;
        }
    }
    
    @Override
    protected synchronized int innerOnNonFatalFailure(int blockNo) {
        int ret = super.innerOnNonFatalFailure(blockNo);
        if(ret > maxRetries && maxRetries != -1) return ret;
        if(ret % cooldownTries == 0) {
            blockCooldownTimes[blockNo] = System.currentTimeMillis() + cooldownTime;
            overallCooldownTime = Math.min(blockCooldownTimes[blockNo], overallCooldownTime); // Must not be left at infinite!
        } else {
            // Fetchable.
            blockCooldownTimes[blockNo] = 0;
            overallCooldownTime = 0;
        }
        return ret;
    }
    
    /** Should be called e.g. when getMaxBlockNumber() changes. */
    public final synchronized void clearCooldown() {
        overallCooldownTime = 0;
    }
    
    @Override
    public synchronized void onUnSuccess(int blockNo) {
        blockCooldownTimes[blockNo] = 0;
        clearCooldown();
    }
    
    public synchronized long overallCooldownTime() {
        return overallCooldownTime;
    }

    public synchronized long getCooldownTime(int blockNumber) {
        if(hasSucceeded(blockNumber)) return 0;
        return blockCooldownTimes[blockNumber];
    }

}
