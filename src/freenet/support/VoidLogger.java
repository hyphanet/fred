/*
 * Created on Mar 18, 2004
 */
package freenet.support;

/**
 * @author Iakin
 * A LoggerHook implementation that just passes any supplied log messages on to /dev/null 
 */
public class VoidLogger extends Logger
{

	public void log(Object o, Class source, String message, Throwable e, int priority) {
	}

	public void log(Object source, String message, int priority) {
	}

	public void log(Object o, String message, Throwable e, int priority) {
	}

	public void log(Class c, String message, int priority) {
	}

	public void log(Class c, String message, Throwable e, int priority) {
	}

	public long minFlags() {
		return 0;
	}

	public long notFlags() {
		return 0;
	}

	public long anyFlags() {
		return 0;
	}

	public boolean instanceShouldLog(int priority, Class c) {
		return false;
	}

	public boolean instanceShouldLog(int prio, Object o) {
		return false;
	}

	public void setThreshold(int thresh) {
	}

	public int getThreshold() {
		return 0;
	}

	public void setThreshold(String symbolicThreshold) {
	}

	public void setDetailedThresholds(String details) {
	}
	
}