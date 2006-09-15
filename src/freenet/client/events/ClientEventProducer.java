/*
  ClientEventProducer.java / Freenet
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


/**
 * Event handling for clients.
 *
 * @author oskar
 */
public interface ClientEventProducer {

    /**
     * Sends the event to all registered EventListeners.
     * @param ce  the ClientEvent to raise
     */
    void produceEvent(ClientEvent ce);
        
    /**
     * Adds an EventListener that will receive all events produced
     * by the implementing object.
     * @param cel The ClientEventListener to add.
     */
    void addEventListener(ClientEventListener cel);

    /**
     * Removes an EventListener that will no loger receive events
     * produced by the implementing object.
     * @param cel  The ClientEventListener to remove.
     * @return     true if a Listener was removed, false otherwise.
     */
    boolean removeEventListener(ClientEventListener cel);
}


