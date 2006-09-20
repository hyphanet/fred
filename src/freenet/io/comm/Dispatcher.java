/*
 * Created on Jan 21, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.io.comm;


/**
 * @author root
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public interface Dispatcher {

    /**
     * Handle a message.
     * @param m
     * @return false if we did not handle the message and want it to be
     * passed on to the next filter.
     */
    boolean handleMessage(Message m);

}
