package freenet.support;
import java.util.Enumeration;
import java.util.Vector;

/**
 * A class that takes logging messages and distributes them to LoggerHooks.
 * It implements LoggerHook itself, so that instances can be chained (just
 * don't create loops).
 */
public class LoggerHookChain extends LoggerHook {
  
    private Vector hooks;

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
        hooks = new Vector();
    }
    public LoggerHookChain(String threshold) {
    	super(threshold);
        hooks = new Vector();
    }
  

    /**
     * This is the implementation of LoggerHook method, which allows
     * one logger receive events from another.
     * @implements LoggerHook.log()
     */
    public void log(Object o, Class c, String msg, Throwable e, int priority){
        for(Enumeration en = hooks.elements(); en.hasMoreElements();) {
            ((LoggerHook) en.nextElement()).log(o,c,msg,e,priority);
        }
    }

    /**
     * Add a hook which will be called every time a message is logged
     */
    public void addHook(LoggerHook lh) {
        hooks.addElement(lh);
    }

    /**
     * Remove a hook from the logger.
     */
    public void removeHook(LoggerHook lh) {
        hooks.removeElement(lh);
        hooks.trimToSize();
    }

    /**
     * Returns all the current hooks.
     */
    public LoggerHook[] getHooks() {
        LoggerHook[] r = new LoggerHook[hooks.size()];
        hooks.copyInto(r);
        return r;
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

	public void setDetailedThresholds(String details) {
		super.setDetailedThresholds(details);
		LoggerHook[] hooks = getHooks();
		for (int i = 0; i < hooks.length; i++)
			hooks[i].setDetailedThresholds(details);
	}
	public void setThreshold(int thresh) {
		super.setThreshold(thresh);
		LoggerHook[] hooks = getHooks();
		for (int i = 0; i < hooks.length; i++)
			hooks[i].setThreshold(thresh);
	}
}

