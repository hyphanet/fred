package freenet.client.events;


/**
 * Event handling for clients.
 *
 * @author oskar
 **/


public interface ClientEventListener {

    /**
     * Hears an event.
     **/
    public void receive(ClientEvent ce);

}
