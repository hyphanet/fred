/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.io;

//~--- JDK imports ------------------------------------------------------------

import java.net.InetAddress;

public class EverythingMatcher implements AddressMatcher {
    public EverythingMatcher() {}

    @Override
    public boolean matches(InetAddress address) {
        return true;
    }

    @Override
    public String getHumanRepresentation() {
        return "*";
    }
}
