/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.BooleanCallback;

public class NullBooleanCallback extends BooleanCallback {
    @Override
    public Boolean get() {
        return false;
    }

    @Override
    public void set(Boolean val) throws InvalidConfigValueException {

        // Ignore
    }
}
