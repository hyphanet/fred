/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

public class NullSendableRequestItem implements SendableRequestItem, SendableRequestItemKey {
    public static final NullSendableRequestItem nullItem = new NullSendableRequestItem();

    @Override
    public void dump() {

        // Do nothing, we will be GC'ed.
    }

    @Override
    public SendableRequestItemKey getKey() {
        return this;
    }
}
