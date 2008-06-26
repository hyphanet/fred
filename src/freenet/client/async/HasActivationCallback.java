package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * Public interface implemented by nonpublic classes (e.g. nonpublic inner 
 * classes) that want db4o to call their objectOnActivation callback.
 * @author toad
 *
 */
public interface HasActivationCallback {
	
	public void objectOnActivate(ObjectContainer container);

}
