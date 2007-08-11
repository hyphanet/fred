/**
 * @author toad
 * To the extent that this is copyrightable, it's part of Freenet and licensed 
 * under GPL2 or later. However, it's a trivial interface taken from Sun JDK 1.5,
 * and we will use that when we migrate to 1.5.
 */
package freenet.support;

public interface Executor {
	
	/** Execute a job. */
	public void execute(Runnable job, String jobName);

}
