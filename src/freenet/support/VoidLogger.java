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
	public void log(Object o, Class<?> source, String message, Throwable e, LogLevel priority) {
	}

	@Override
	public void log(Object source, String message, LogLevel priority) {
	}

	@Override
	public void log(Object o, String message, Throwable e, LogLevel priority) {
	}

	@Override
	public void log(Class<?> c, String message, LogLevel priority) {
	}

	@Override
	public void log(Class<?> c, String message, Throwable e, LogLevel priority) {
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
	public boolean instanceShouldLog(LogLevel priority, Class<?> c) {
		return false;
	}

	@Override
	public boolean instanceShouldLog(LogLevel prio, Object o) {
		return false;
	}

	@Override
	public void setThreshold(LogLevel thresh) {
	}

	@Override
	public LogLevel getThresholdNew() {
		return LogLevel.NONE;
	}

	@Override
	public void setThreshold(String symbolicThreshold) {
	}

	@Override
	public void setDetailedThresholds(String details) {
	}

	@Override
	public final void instanceRegisterLogThresholdCallback(LogThresholdCallback ltc) {}
	
	@Override
	public final void instanceUnregisterLogThresholdCallback(LogThresholdCallback ltc) {}
}
