/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.ShortCallback;

public class NullShortCallback extends ShortCallback {
    @Override
    public Short get() {
        return 0;
    }

    @Override
    public void set(Short val) throws InvalidConfigValueException {

        // Ignore
    }
}
