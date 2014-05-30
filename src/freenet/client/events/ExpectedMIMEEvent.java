/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

public class ExpectedMIMEEvent implements ClientEvent {
    static final int CODE = 0x0B;
    public final String expectedMIMEType;

    public ExpectedMIMEEvent(String type) {
        this.expectedMIMEType = type;
    }

    @Override
    public int getCode() {
        return CODE;
    }

    @Override
    public String getDescription() {
        return "Expected MIME type: " + expectedMIMEType;
    }
}
