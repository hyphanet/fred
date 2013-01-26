/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.support.Logger;

/**
 * Event handeling for clients. SimpleEventProducer is a simple
 * ClientEventProducer implementation that can be used for others.
 *
 * @author oskar
 **/
public class SimpleEventProducer implements ClientEventProducer {

    private Vector<ClientEventListener> listeners;

    /**
     * Create a new SimpleEventProducer
     *
     **/
    public SimpleEventProducer() {
	listeners = new Vector<ClientEventListener>();
    }
    
    /** Create a new SimpleEventProducer with the given listeners. */
    public SimpleEventProducer(ClientEventListener[] cela) {
	this();
	for (int i = 0 ; i < cela.length ; i++)
	    addEventListener(cela[i]);
    }
    
	@Override
    public void addEventListener(ClientEventListener cel) {
	if(cel != null)
	    listeners.addElement(cel);
	else
	    throw new IllegalArgumentException("Adding a null listener!");
    }
    
	@Override
    public boolean removeEventListener(ClientEventListener cel) {
	boolean b = listeners.removeElement(cel);
	listeners.trimToSize();
	return b;
    }

    /**
     * Sends the ClientEvent to all registered listeners of this object.
     **/
	@Override
    public void produceEvent(ClientEvent ce, ObjectContainer container, ClientContext context) {
    	if(container != null)
    		container.activate(listeners, 1);
	for (Enumeration<ClientEventListener> e = listeners.elements() ; 
	     e.hasMoreElements();) {
            try {
            	ClientEventListener cel = e.nextElement();
            	if(container != null)
            		container.activate(cel, 1);
                cel.receive(ce, container, context);
            } catch (NoSuchElementException ne) {
		Logger.normal(this, "Concurrent modification in "+
				"produceEvent!: "+this);
	    } catch (Exception ue) {
                System.err.println("---Unexpected Exception------------------");
                ue.printStackTrace();
                System.err.println("-----------------------------------------");
            }
	}
    }
    
    /** Returns the listeners as an array. */
    public ClientEventListener[] getEventListeners() {
	ClientEventListener[] ret =
	    new ClientEventListener[listeners.size()];
	listeners.copyInto(ret);
	return ret;
    }

    /** Adds all listeners in the given array. */
    public void addEventListeners(ClientEventListener[] cela) {
	for (int i = 0 ; i < cela.length ; i++)
	    addEventListener(cela[i]);
    }

	@Override
	public void removeFrom(ObjectContainer container) {
    	if(container != null)
    		container.activate(listeners, 1);
		ClientEventListener[] list = listeners.toArray(new ClientEventListener[listeners.size()]);
		listeners.clear();
		container.delete(listeners);
		for(ClientEventListener l: list)
			l.onRemoveEventProducer(container);
		container.delete(this);
	}
}
