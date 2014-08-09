/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Plugins which provide FCP services to clients must implement this interface.
 * 
 * @see FCPPluginClient A client will be represented as this class to the server plugin. Its JavaDoc also contains a nice overview of the code path which 
 *                      FCP plugin messages flow through.
 * @author xor (xor@freenetproject.org)
 */
public interface FredPluginFCPServer {
    
    public static enum ClientPermissions {
        /** The client plugin is running within the same node as the server plugin. TODO FIXME: Is there any reason not to assume {@link #ACCESS_FCP_FULL}? */
        ACCESS_DIRECT,
        ACCESS_FCP_RESTRICTED,
        ACCESS_FCP_FULL
    };
    
    /**
     * @param client The client which sent the message.
     * @param permissions Permissions of the client.
     * @param parameters Part 1 of client message: Human-readable parameters. Shall be small amount of data.
     * @param data Part 2 of client message: Non-human readable, large size bulk data. Can be null.
     */
    void handleFCPPluginClientMessage(FCPPluginClient client, ClientPermissions permissions, SimpleFieldSet parameters, Bucket data);

}
