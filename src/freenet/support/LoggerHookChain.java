package freenet.support;

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
        this(NORMAL);
    }

    /**
     * Create a logger.
     * @param threshold   Suppress all log calls with lower priority then 
     *                     this.
     */
    public LoggerHookChain(int threshold) {
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
    public void log(Object o, Class c, String msg, Throwable e, int priority){
        LoggerHook[] myHooks = hooks;
        for(int i=0;i<myHooks.length;i++) {
            myHooks[i].log(o,c,msg,e,priority);
        }
    }

    /**
     * Add a hook which will be called every time a message is logged
     */
    public synchronized void addHook(LoggerHook lh) {
        LoggerHook[] newHooks = new LoggerHook[hooks.length+1];
        System.arraycopy(newHooks, 0, hooks, 0, hooks.length);
        newHooks[hooks.length] = lh;
        hooks = newHooks;
    }

    /**
     * Remove a hook from the logger.
     */
    public synchronized void removeHook(LoggerHook lh) {
        LoggerHook[] newHooks = new LoggerHook[hooks.length-1];
        int x=0;
        boolean removed = false;
        for(int i=0;i<hooks.length;i++) {
            if(hooks[i] == lh) {
                removed = true;
            } else {
                newHooks[x++] = hooks[i];
            }
        }
        if(!removed) return;
        if(x == newHooks.length) {
            hooks = newHooks;
        } else {
            LoggerHook[] finalHooks = new LoggerHook[x];
            System.arraycopy(newHooks, 0, finalHooks, 0, x);
            hooks = finalHooks;
        }
    }

    /**
     * Returns all the current hooks.
     */
    public LoggerHook[] getHooks() {
        return hooks;
    }

    public long minFlags()
    {
    	return 0;
    }

    public long notFlags()
    {
    	return 0;
    }

    public long anyFlags()
    {
    	return ((2*ERROR)-1) & ~(threshold-1);
    }

	public void setDetailedThresholds(String details) throws InvalidThresholdException {
		super.setDetailedThresholds(details);
		LoggerHook[] h = getHooks();
		for (int i = 0; i < h.length; i++)
			h[i].setDetailedThresholds(details);
	}
	public void setThreshold(int thresh) {
		super.setThreshold(thresh);
		LoggerHook[] h = getHooks();
		for (int i = 0; i < h.length; i++)
			h[i].setThreshold(thresh);
	}
}

