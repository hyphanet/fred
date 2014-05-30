/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.pluginmanager;

//~--- JDK imports ------------------------------------------------------------

import java.util.Map;

/**
 * Callback called by port forwarding plugins to indicate success or failure.
 * @author toad
 */
public interface ForwardPortCallback {

    /** Called to indicate status on one or more forwarded ports. */
    public void portForwardStatus(Map<ForwardPort, ForwardPortStatus> statuses);
}
