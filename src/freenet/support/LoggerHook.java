package freenet.support;

import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import freenet.l10n.NodeL10n;

public abstract class LoggerHook extends Logger {

	protected LoggerPriority threshold;

	public static final class DetailedThreshold {
		final String section;
		final LoggerPriority dThreshold;
		public DetailedThreshold(String section, LoggerPriority thresh) {
			this.section = section;
			this.dThreshold = thresh;
		}
	}

	protected LoggerHook(LoggerPriority thresh){
		this.threshold = thresh;
	}

	LoggerHook(String thresh) throws InvalidThresholdException{
		this.threshold = priorityOf(thresh);
	}

	public DetailedThreshold[] detailedThresholds = new DetailedThreshold[0];
	private CopyOnWriteArrayList<LogThresholdCallback> thresholdsCallbacks = new CopyOnWriteArrayList<LogThresholdCallback>();

	/**
	 * Log a message
	 * 
	 * @param o
	 *            The object where this message was generated.
	 * @param source
	 *            The class where this message was generated.
	 * @param message
	 *            A clear and verbose message describing the event
	 * @param e
	 *            Logs this exception with the message.
	 * @param priority
	 *            The priority of the mesage, one of LoggerPriority.ERROR,
	 *            LoggerPriority.NORMAL, LoggerPriority.MINOR, or LoggerPriority.DEBUG.
	 */
	@Override
	public abstract void log(
			Object o,
			Class<?> source,
			String message,
			Throwable e,
			LoggerPriority priority);

	/**
	 * Log a message.
	 * @param source        The source object where this message was generated
	 * @param message A clear and verbose message describing the event
	 * @param priority The priority of the mesage, one of LoggerPriority.ERROR,
	 *                 LoggerPriority.NORMAL, LoggerPriority.MINOR, or LoggerPriority.DEBUG.
	 **/
	@Override
	public void log(Object source, String message, LoggerPriority priority) {
		if (!instanceShouldLog(priority,source)) return;
		log(source, source == null ? null : source.getClass(), 
				message, null, priority);
	}

	/** 
	 * Log a message with an exception.
	 * @param o   The source object where this message was generated.
	 * @param message  A clear and verbose message describing the event.
	 * @param e        Logs this exception with the message.
	 * @param priority The priority of the mesage, one of LoggerPriority.ERROR,
	 *                 LoggerPriority.NORMAL, LoggerPriority.MINOR, or LoggerPriority.DEBUG.
	 * @see #log(Object o, String message, LoggerPriority priority)
	 */
	@Override
	public void log(Object o, String message, Throwable e, 
			LoggerPriority priority) {
		if (!instanceShouldLog(priority,o)) return;
		log(o, o == null ? null : o.getClass(), message, e, priority);
	}

	/**
	 * Log a message from static code.
	 * @param c        The class where this message was generated.
	 * @param message  A clear and verbose message describing the event
	 * @param priority The priority of the mesage, one of LoggerPriority.ERROR,
	 *                 LoggerPriority.NORMAL, LoggerPriority.MINOR, or LoggerPriority.DEBUG.
	 */
	@Override
	public void log(Class<?> c, String message, LoggerPriority priority) {
		if (!instanceShouldLog(priority,c)) return;
		log(null, c, message, null, priority);
	}


	@Override
	public void log(Class<?> c, String message, Throwable e, LoggerPriority priority) {
		if (!instanceShouldLog(priority, c))
			return;
		log(null, c, message, e, priority);
	}

	public boolean acceptPriority(LoggerPriority prio) {
		return prio.matchesThreshold(threshold);
	}

	@Override
	public void setThreshold(LoggerPriority thresh) {
		this.threshold = thresh;
		notifyLogThresholdCallbacks();
	}

	@Override
	public LoggerPriority getThreshold() {
		return threshold;
	}

	@Override
	public void setThreshold(String symbolicThreshold) throws InvalidThresholdException {
		setThreshold(priorityOf(symbolicThreshold));
	}

	@Override
	public void setDetailedThresholds(String details) throws InvalidThresholdException {
		if (details == null)
			return;
		StringTokenizer st = new StringTokenizer(details, ",", false);
		ArrayList<DetailedThreshold> stuff = new ArrayList<DetailedThreshold>();
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (token.length() == 0)
				continue;
			int x = token.indexOf(':');
			if (x < 0)
				continue;
			if (x == token.length() - 1)
				continue;
			String section = token.substring(0, x);
			String value = token.substring(x + 1, token.length());
			LoggerPriority thresh = LoggerHook.priorityOf(value);
			stuff.add(new DetailedThreshold(section, thresh));
		}
		DetailedThreshold[] newThresholds = new DetailedThreshold[stuff.size()];
		stuff.toArray(newThresholds);
		synchronized(this) {
			detailedThresholds = newThresholds;
			notifyLogThresholdCallbacks();
		}
	}

	public String getDetailedThresholds() {
		DetailedThreshold[] thresh = null;
		synchronized(this) {
			thresh = detailedThresholds;
		}
		StringBuilder sb = new StringBuilder();
		for(int i=0;i<thresh.length;i++) {
			if(i != 0)
				sb.append(',');
			sb.append(thresh[i].section);
			sb.append(':');
			sb.append(thresh[i].dThreshold.name());
		}
		return sb.toString();
	}

	/**
	 * Returns the priority level matching the string. If no priority
	 * matches, LoggerPriority.NORMAL is returned.
	 * @param s  A string matching one of the logging priorities, case
	 *           insensitive.
	 **/
	public static LoggerPriority priorityOf(String s) throws InvalidThresholdException {
		try {
			return LoggerPriority.valueOf(s.toUpperCase());
		}
		catch(IllegalArgumentException e) {
			throw new InvalidThresholdException(NodeL10n.getBase().getString("LoggerHook.unrecognisedPriority", "name", s));
		}
	}

	public static class InvalidThresholdException extends Exception {
		private static final long serialVersionUID = -1;

		InvalidThresholdException(String msg) {
			super(msg);
		}
	}

	@Override
	public boolean instanceShouldLog(LoggerPriority priority, Class<?> c) {
		DetailedThreshold[] thresholds;
		LoggerPriority thresh;
		synchronized(this) {
			thresholds = detailedThresholds;
			thresh = threshold;
		}
		if ((c != null) && (thresholds.length > 0)) {
			String cname = c.getName();
				for(DetailedThreshold dt : thresholds) {
					if(cname.startsWith(dt.section))
						thresh = dt.dThreshold;
				}
		}
		return priority.matchesThreshold(thresh);
	}

	@Override
	public final boolean instanceShouldLog(LoggerPriority prio, Object o) {
		return instanceShouldLog(prio, o == null ? null : o.getClass());
	}

	@Override
	public final void instanceRegisterLogThresholdCallback(LogThresholdCallback ltc) {
		thresholdsCallbacks.add(ltc);

		// Call the new callback to avoid code duplication
		ltc.shouldUpdate();
	}
	
	@Override
	public final void instanceUnregisterLogThresholdCallback(LogThresholdCallback ltc) {
		thresholdsCallbacks.remove(ltc);
	}

	private final void notifyLogThresholdCallbacks() {
		for(LogThresholdCallback ltc : thresholdsCallbacks)
			ltc.shouldUpdate();
	}

}
