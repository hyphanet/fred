/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.MessageDigest;

import org.bitpedia.collider.core.Ed2Handler;

/**
** Implementation of {@link MessageDigest} backed by a {@link Ed2Handler}.
**
** @author infinity0
** @author toad
*/
public class Ed2MessageDigest extends MessageDigest {

	final protected Ed2Handler handler;

	public Ed2MessageDigest() {
		super("ED2K");
		handler = new Ed2Handler();
		handler.analyzeInit();
	}

	@Override
	protected byte[] engineDigest() {
		return handler.analyzeFinal();
	}

	@Override
	protected void engineReset() {
		handler.analyzeInit();
	}

	@Override
	protected void engineUpdate(byte arg0) {
		engineUpdate(new byte[] { arg0 }, 0, 1);
	}

	@Override
	protected void engineUpdate(byte[] arg0, int arg1, int arg2) {
		handler.analyzeUpdate(arg0, arg1, arg2);
	}

	@Override
	protected int engineGetDigestLength() {
		return 16;
	}

}
