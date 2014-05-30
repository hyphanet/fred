/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.LongCallback;

public class NullLongCallback extends LongCallback {
    @Override
    public Long get() {
        return 0L;
    }

    @Override
    public void set(Long val) throws InvalidConfigValueException {

        // Ignore
    }
}
