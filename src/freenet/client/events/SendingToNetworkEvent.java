/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

public class SendingToNetworkEvent implements ClientEvent {
    final static int CODE = 0x0A;

    @Override
    public int getCode() {
        return CODE;
    }

    @Override
    public String getDescription() {
        return "Sending to network";
    }
}
