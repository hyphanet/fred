package freenet.client.async;

public class RegisterMeSortKey implements Comparable {

	final long addedTime;
	final short priority;
	
	public RegisterMeSortKey(short priorityClass) {
		this.addedTime = System.currentTimeMillis();
		this.priority = priorityClass;
	}

	public int compareTo(Object arg0) {
		RegisterMeSortKey key = (RegisterMeSortKey) arg0;
		if(key.priority < priority)
			return -1;
		if(key.priority > priority)
			return 1;
		if(key.addedTime < addedTime)
			return -1;
		if(key.addedTime > addedTime)
			return 1;
		return 0;
	}
	
	public boolean equals(Object arg0) {
		if(arg0 == this) return true;
		if(!(arg0 instanceof RegisterMeSortKey)) return false;
		RegisterMeSortKey key = (RegisterMeSortKey) arg0;
		return key.priority == priority && key.addedTime == addedTime;
	}
	
	public int hashCode() {
		return (int) addedTime ^ ((int) addedTime >>> 32) ^ priority;
	}

}
