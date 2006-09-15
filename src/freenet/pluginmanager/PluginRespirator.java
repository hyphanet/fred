/*
  PluginRespirator.java / Freenet
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.pluginmanager;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.node.RequestStarter;

public class PluginRespirator {
	private HighLevelSimpleClient hlsc = null;
	private Node node;
	
	public PluginRespirator(Node node, PluginManager pm) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
	
	public Node getNode(){
		return node;
	}
}
