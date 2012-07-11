package freenet.node;

import freenet.pluginmanager.StreamTransportPlugin;

/**
 * A bundle of objects needed for connections. I could have implemented it using PeerTransport, but to keep the design simple
 * I have created a separate object for this. Can be expanded to include other common objects.
 * @author chetan
 *
 */
public class StreamTransportBundle {
	
	public final String transportName;
	
	public StreamTransportPlugin transportPlugin;
	
	public OutgoingStreamMangler streamMangler;
	
	public StreamTransportBundle(StreamTransportPlugin transportPlugin, OutgoingStreamMangler streamMangler){
		this.transportName = transportPlugin.transportName;
		this.transportPlugin = transportPlugin;
		this.streamMangler = streamMangler;
	}

}
