/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import java.io.Serializable;
import java.util.ArrayList;

import freenet.client.async.ClientContext;

/**
 * Event handeling for clients. SimpleEventProducer is a simple
 * ClientEventProducer implementation that can be used for others.
 * 
 * @author oskar
 **/
public class SimpleEventProducer implements ClientEventProducer, Serializable {

    private static final long serialVersionUID = 1L;
    private ArrayList<ClientEventListener> listeners;

    /**
     * Create a new SimpleEventProducer
     * 
     **/
    public SimpleEventProducer() {
        listeners = new ArrayList<ClientEventListener>();
    }

    /** Create a new SimpleEventProducer with the given listeners. */
    public SimpleEventProducer(ClientEventListener[] cela) {
        this();
        for (int i = 0; i < cela.length; i++)
            addEventListener(cela[i]);
    }

    @Override
    public synchronized void addEventListener(ClientEventListener cel) {
        if (cel != null)
            listeners.add(cel);
        else
            throw new IllegalArgumentException("Adding a null listener!");
    }

    @Override
    public synchronized boolean removeEventListener(ClientEventListener cel) {
        boolean b = listeners.remove(cel);
        listeners.trimToSize();
        return b;
    }

    /**
     * Sends the ClientEvent to all registered listeners of this object.
     * 
     * Please do not change SimpleEventProducer to always produce events off-thread, it
     * is better to run the client layer method that produces the event off-thread, because events 
     * could be re-ordered, which matters for some events notably SimpleProgressEvent.
     * See e.g. ClientGetter.innerNotifyClients()),  
     **/
    @Override
    public void produceEvent(ClientEvent ce, ClientContext context) {
        // Events are relatively uncommon. Consistency more important than speed.
        ClientEventListener[] list;
        synchronized(this) {
            list = getEventListeners();
        }
        for (ClientEventListener cel : list) {
            try {
                cel.receive(ce, context);
            } catch (Exception ue) {
                System.err.println("---Unexpected Exception------------------");
                ue.printStackTrace();
                System.err.println("-----------------------------------------");
            }
        }
    }

    /** Returns the listeners as an array. */
    public synchronized ClientEventListener[] getEventListeners() {
        ClientEventListener[] ret = new ClientEventListener[listeners.size()];
        return listeners.toArray(ret);
    }

    /** Adds all listeners in the given array. */
    public synchronized void addEventListeners(ClientEventListener[] cela) {
        for (int i = 0; i < cela.length; i++)
            addEventListener(cela[i]);
    }

}
