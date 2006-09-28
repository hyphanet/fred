/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import freenet.support.HTMLEncoder;

public class PrivkeyHasBeenBlownException extends Exception{	
	private static final long serialVersionUID = -1;
	
	PrivkeyHasBeenBlownException(String msg) {
		super("The project's private key has been blown, meaning that it has been compromized"+
			  "and shouldn't be trusted anymore. Please get a new build by hand and verify CAREFULLY"+
			  "its signature and CRC. Here is the revocation message: "+HTMLEncoder.encode(msg));
	}
}
