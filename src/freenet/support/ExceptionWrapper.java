package freenet.support;

public class ExceptionWrapper {
	
	private Exception e;
	
	public synchronized Exception get() {
		return e;
	}
	
	public synchronized void set(Exception e) {
		this.e = e;
	}

}
