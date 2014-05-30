/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.InsertContext.CompatibilityMode;

public class SplitfileCompatibilityModeEvent implements ClientEvent {
    public final static int CODE = 0x0D;
    public final long minCompatibilityMode;
    public final long maxCompatibilityMode;
    public final byte[] splitfileCryptoKey;
    public final boolean dontCompress;
    public final boolean bottomLayer;

    public SplitfileCompatibilityModeEvent(CompatibilityMode min, CompatibilityMode max, byte[] splitfileCryptoKey,
            boolean dontCompress, boolean bottomLayer) {
        this.minCompatibilityMode = min.ordinal();
        this.maxCompatibilityMode = max.ordinal();
        this.splitfileCryptoKey = splitfileCryptoKey;
        this.dontCompress = dontCompress;
        this.bottomLayer = bottomLayer;
    }

    @Override
    public int getCode() {
        return CODE;
    }

    @Override
    public String getDescription() {
        if (minCompatibilityMode == -1) {
            return "Unknown CompatibilityMode";
        } else {
            return "CompatibilityMode between " + minCompatibilityMode + " and " + maxCompatibilityMode;
        }
    }
}
