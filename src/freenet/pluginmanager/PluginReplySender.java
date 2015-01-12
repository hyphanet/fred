/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.clients.fcp.FCPConnectionInputHandler;
import freenet.clients.fcp.FCPPluginClientMessage;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;

/** @deprecated Use the {@link FCPPluginConnection} API instead. */
@Deprecated
public abstract class PluginReplySender {
	
	final String pluginname;
    
    /**
     * Identifier of the connection to the client. Randomly chosen for each connection.
     */
    final String clientIdentifier;
	
	/**
     * Specified by the client through FCP via the "Identifier" field.
     * As the client can specify this freely, it is not sufficient as an ID. Thats why there also is another {@link #clientIdentifier}.
	 */
	final String clientSideIdentifier;
    
	
    /**
     * @param clientIdentifier Identifier of the particular network connection to the client. Should be a random UUID chosen for each network connection.
     * @param clientSideIdentifier Specified by the client through FCP via the "Identifier" field. As the client can specify this freely, it is not sufficient
     *                             as an ID. Thats why there also is another parameter clientIdentifier.
     */
	public PluginReplySender(String pluginname2, String clientIdentifier, String clientSideIdentifier) {
		pluginname = pluginname2;
		this.clientIdentifier = clientIdentifier;
		this.clientSideIdentifier = clientSideIdentifier;
	}
	
	public String getPluginName() {
		return pluginname;
	}
	
	/**
     * @return An identifier of the connection specified by the client through FCP via the "Identifier" field.
     *         As the client can specify this freely, it is not sufficient as an ID. Thats why there also is {@link #getConnectionIdentifier()}.
	 */
	public String getIdentifier() {
		return clientSideIdentifier;
	}

	public void send(SimpleFieldSet params) throws PluginNotFoundException {
		send(params, (Bucket)null);
	}
	
	public void send(SimpleFieldSet params, byte[] data) throws PluginNotFoundException {
		if (data == null)
			send(params, (Bucket)null);
		else
			send(params, new ArrayBucket(data));
	}
	
	public abstract void send(SimpleFieldSet params, Bucket bucket) throws PluginNotFoundException;

    /**
     * <p>Gets an unique identifier of the connection to the client.</p>
     * <p><b>Notice:</b> The client can cause one network connection to appear with different connection IDs by setting a different <i>Identifier</i> field in
     * the contents of multiple FCP messages: The <i>Identifier</i> set by the client is part of the returned connection ID. See {@link #getIdentifier()}.</p>
     * 
     * <p>This allows you to identify whether two PluginReplySender objects which you received through multiple callbacks of
     * {@link FredPluginFCP#handle(PluginReplySender, SimpleFieldSet, Bucket, int)} belong to the same client connection:<br/>
     * If the connection is the same, and the FCP field <i>Identifier</i> by the client is the same, the return value of this function is
     * {@link String#equals(Object)}.<br/>
     * If the return value is not equals(), then the PlugplinReplySenders belong to a different client, to a different connection of the same client, or
     * to a different <i>Identifier</i> specified by the client.<br/></p>
     * 
     * <p>This mechanism is necessary because the {@link FCPConnectionInputHandler} will create a fresh PluginReplySender for each message it receives from
     * the same client. <br/>Therefore, object identity of the PluginReplySender is not sufficient for identifying connections.<br/>
     * (Actually it creates a fresh {@link FCPPluginClientMessage} for every processed message,
     * which in turn creates a fresh {@link PluginTalker}, which then creates a fresh
     * PluginReplySender).</p>
     */
    public final String getConnectionIdentifier() {
        return clientIdentifier + ":" + clientSideIdentifier;
    }
}
