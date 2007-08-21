/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;

/** Thrown when Metadata parse fails. */
public class MetadataParseException extends Exception {

	private static final long serialVersionUID = 4910650977022715220L;

	public MetadataParseException(String string) {
		super(string);
	}

}
