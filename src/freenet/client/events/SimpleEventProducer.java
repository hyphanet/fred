/*
  SimpleElementProducer.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.client.events;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.Vector;

import freenet.support.Logger;

/**
 * Event handeling for clients. SimpleEventProducer is a simple
 * ClientEventProducer implementation that can be used for others.
 *
 * @author oskar
 **/
public class SimpleEventProducer implements ClientEventProducer {

    private Vector listeners;

    /**
     * Create a new SimpleEventProducer
     *
     **/
    public SimpleEventProducer() {
	listeners = new Vector();
    }
    
    /** Create a new SimpleEventProducer with the given listeners. */
    public SimpleEventProducer(ClientEventListener[] cela) {
	this();
	for (int i = 0 ; i < cela.length ; i++)
	    addEventListener(cela[i]);
    }
    
    public void addEventListener(ClientEventListener cel) {
	if(cel != null)
	    listeners.addElement(cel);
	else
	    throw new IllegalArgumentException("Adding a null listener!");
    }
    
    public boolean removeEventListener(ClientEventListener cel) {
	boolean b = listeners.removeElement(cel);
	listeners.trimToSize();
	return b;
    }

    /**
     * Sends the ClientEvent to all registered listeners of this object.
     **/
    public void produceEvent(ClientEvent ce) {
	for (Enumeration e = listeners.elements() ; 
	     e.hasMoreElements();) {
            try {
                ((ClientEventListener) e.nextElement()).receive(ce);
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
}
