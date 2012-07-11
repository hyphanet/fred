package freenet.pluginmanager;

import java.io.InputStream;
import java.io.OutputStream;

import freenet.io.comm.IncomingStreamHandler;
import freenet.node.Node;
import freenet.node.TransportManager.TransportMode;

/**
 * 
 * @author chetan
 *
 */
public abstract class StreamTransportPlugin extends TransportPlugin {
	
	public StreamTransportPlugin(String transportName, TransportMode transportMode, Node node) {
		super(TransportType.streams, transportName, transportMode, node);
	}
	
	/**
	 * Method to connect to a peer
	 * @param destination The peer address to connect to
	 * @return A handle that contains the stream objects and more methods as required
	 */
	public abstract PluginStreamHandler connect(PluginAddress destination);
	
	/**
	 * Method to make a stream plugin listen to connections
	 * @param handle Object to pass new connections
	 * @param pluginAddress Address to listen at
	 * @return A handle that can be used to terminate the listener
	 */
	public abstract PluginConnectionListener listen(IncomingStreamHandler handle);
	
}

/**
 * This object will used by the node to read and write data using streams.
 * The plugin provides these streams and the plugin will take care of parsing the data.
 * @author chetan
 * 
 */
interface PluginStreamHandler{
	
	public InputStream getInputStream();
	
	public OutputStream getOutputStream();
	
	public void disconnect();
	
}
/**
 * A handle for the listener object, used by node to terminate.
 * Further methods maybe needed
 * @author chetan
 *
 */
interface PluginConnectionListener{
	
	public void close();
	
}

