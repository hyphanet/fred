/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

public class ExpectedFileSizeEvent implements ClientEvent {
    static final int CODE = 0x0C;
    public final long expectedSize;

    public ExpectedFileSizeEvent(long size) {
        expectedSize = size;
    }

    @Override
    public int getCode() {
        return CODE;
    }

    @Override
    public String getDescription() {
        return "Expected file size: " + expectedSize;
    }
}
