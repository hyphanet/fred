/*
 * This code is part of Freenet. It is distributed under the GNU General Public
 * License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * Message that disconnects a client.
 *
 * @author <a href="mailto:bombe@freenetproject.org">David ‘Bombe’ Roden</a>
 */
public class DisconnectMessage extends FCPMessage {

	/** The name of this message. */
	public static final String NAME = "Disconnect";

	/**
	 * Creates a new disconnect message.
	 *
	 * @param simpleFieldSet
	 *            The field set to create the message from
	 */
	public DisconnectMessage(SimpleFieldSet simpleFieldSet) {
		/* do nothing. */
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see freenet.node.fcp.FCPMessage#getFieldSet()
	 */
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see freenet.node.fcp.FCPMessage#getName()
	 */
	@Override
	public String getName() {
		return NAME;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see freenet.node.fcp.FCPMessage#removeFrom(com.db4o.ObjectContainer)
	 */
	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see freenet.node.fcp.FCPMessage#run(freenet.node.fcp.FCPConnectionHandler,
	 *      freenet.node.Node)
	 */
	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		handler.close();
	}

}
