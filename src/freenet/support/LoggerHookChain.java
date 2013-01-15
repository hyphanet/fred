package freenet.support;

import java.util.Arrays;


/**
 * A class that takes logging messages and distributes them to LoggerHooks.
 * It implements LoggerHook itself, so that instances can be chained (just
 * don't create loops).
 */
public class LoggerHookChain extends LoggerHook {

    // Best performance, least synchronization.
    // We will only very rarely add or remove hooks
    private LoggerHook[] hooks;

    /**
     * Create a logger. Threshhold set to NORMAL.
     */
    public LoggerHookChain() {
        this(LogLevel.NORMAL);
    }

    /**
     * Create a logger.
     * @param threshold   Suppress all log calls with lower priority then 
     *                     this.
     */
    public LoggerHookChain(LogLevel threshold) {
        super(threshold);
        hooks = new LoggerHook[0];
    }
    public LoggerHookChain(String threshold) throws InvalidThresholdException {
    	super(threshold);
        hooks = new LoggerHook[0];
    }
  

    /**
     * This is the implementation of LoggerHook method, which allows
     * one logger receive events from another.
     * @implements LoggerHook.log()
     */
    @Override
	public synchronized void log(Object o, Class<?> c, String msg, Throwable e, LogLevel priority) {
        for(LoggerHook hook: hooks) {
            hook.log(o,c,msg,e,priority);
        }
    }

    /**
     * Add a hook which will be called every time a message is logged
     */
    public synchronized void addHook(LoggerHook lh) {
        LoggerHook[] newHooks = Arrays.copyOf(hooks, hooks.length+1);
        newHooks[hooks.length] = lh;
        hooks = newHooks;
    }

    /**
     * Remove a hook from the logger.
     */
    public synchronized void removeHook(LoggerHook lh) {
        final int hooksLength = hooks.length;
        if(hooksLength == 0) return;
        LoggerHook[] newHooks = new LoggerHook[hooksLength-1];
        int x=0;
        for(int i=0;i<hooksLength;i++) {
            if(hooks[i] == lh) continue;
            if(x == newHooks.length) return; // nothing matched
            newHooks[x++] = hooks[i];
        }
        if(x == newHooks.length) {
            hooks = newHooks;
        } else {
            hooks = Arrays.copyOf(newHooks, x);
        }
    }

    /**
     * Returns all the current hooks.
     */
    public synchronized LoggerHook[] getHooks() {
        return hooks;
    }

	@Override
	public void setDetailedThresholds(String details) throws InvalidThresholdException {
		super.setDetailedThresholds(details);
		// No need to tell subordinates, we will do the filtering.
//		LoggerHook[] h = ;
//		for (LoggerHook h: getHooks())
//			h.setDetailedThresholds(details);
	}
	@Override
	public void setThreshold(LogLevel thresh) {
		super.setThreshold(thresh);
//		for (LoggerHook h: getHooks())
//			h.setThreshold(thresh);
	}
}

