/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

//~--- non-JDK imports --------------------------------------------------------

import freenet.crypt.HashResult;

public class ExpectedHashesEvent implements ClientEvent {
    public final static int CODE = 0x0E;
    public final HashResult[] hashes;

    public ExpectedHashesEvent(HashResult[] h) {
        hashes = h;
    }

    @Override
    public int getCode() {
        return CODE;
    }

    @Override
    public String getDescription() {
        return "Expected hashes";
    }
}
