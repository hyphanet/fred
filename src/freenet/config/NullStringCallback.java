/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.StringCallback;

public class NullStringCallback extends StringCallback {
    @Override
    public String get() {
        return "";
    }

    @Override
    public void set(String val) throws InvalidConfigValueException {

        // Ignore
    }
}
