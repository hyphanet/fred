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

	@Override
	public void log(Object o, Class<?> source, String message, Throwable e, int priority) {
	}

	@Override
	public void log(Object source, String message, int priority) {
	}

	@Override
	public void log(Object o, String message, Throwable e, int priority) {
	}

	@Override
	public void log(Class<?> c, String message, int priority) {
	}

	@Override
	public void log(Class<?> c, String message, Throwable e, int priority) {
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

	@Override
	public boolean instanceShouldLog(int priority, Class<?> c) {
		return false;
	}

	@Override
	public boolean instanceShouldLog(int prio, Object o) {
		return false;
	}

	@Override
	public void setThreshold(int thresh) {
	}

	@Override
	public int getThreshold() {
		return 0;
	}

	@Override
	public void setThreshold(String symbolicThreshold) {
	}

	@Override
	public void setDetailedThresholds(String details) {
	}
	
}