package freenet.support;

import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.support.Logger.LogLevel;


@SuppressWarnings("serial") 
public class DebuggingHashMap<K extends Object, V extends Object> extends HashMap<K, V> {
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanUpdate() on DebuggingHashMap "+this+" stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this)+" size="+size(), new Exception("debug"));
		return true;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanNew() on DebuggingHashMap "+this+" stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this)+" size="+size(), new Exception("debug"));
		return true;
	}
	
	public void objectOnUpdate(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectOnUpdate() on DebuggingHashMap "+this+" stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this)+" size="+size(), new Exception("debug"));
	}
	
	public void objectOnNew(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectOnNew() on DebuggingHashMap "+this+" stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this)+" size="+size(), new Exception("debug"));
	}
	
	//private transient boolean activating = false;
	
	public boolean objectCanActivate(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanActivate() on DebuggingHashMap stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this)+" size="+size(), new Exception("debug"));
		
		/** FIXME: This was an attempt to ensure we always activate to depth 2. It didn't work. :( */
		
//		if(activating) {
//			activating = false;
//			return true;
//		}
//		activating = true;
//		container.activate(this, 2);
//		return false;
		return true;
	}
	
}