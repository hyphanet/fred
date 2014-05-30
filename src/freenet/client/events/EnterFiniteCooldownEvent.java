/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.events;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.TimeUtil;

public class EnterFiniteCooldownEvent implements ClientEvent {
    static final int CODE = 0x10;
    public final long wakeupTime;

    public EnterFiniteCooldownEvent(long wakeupTime) {
        this.wakeupTime = wakeupTime;
    }

    @Override
    public String getDescription() {
        return "Wake up in " + TimeUtil.formatTime(wakeupTime - System.currentTimeMillis(), 2, true);
    }

    @Override
    public int getCode() {
        return CODE;
    }
}
