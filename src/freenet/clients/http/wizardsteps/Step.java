/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http.wizardsteps;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.HTTPRequest;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

/**
 * Classes which implement Step are sub-Toadlets accessible only through the wizard, and are not registered in FProxy.
 */
public interface Step {

    /**
     * Renders a page for a step in the wizard by modifying contentNode.
     * @param request The HTTPRequest for the page, used in its creation.
     * @param helper used to get a style-conforming page content node, forms, and InfoBoxes without access to
     * ToadletContext.
     */
    public void getStep(HTTPRequest request, PageHelper helper);

    /**
     * Performs operations for the step.
     * @param request Parameters to inform the step.
     * @return a destination to redirect to.
     * @throws IOException likely a setting failed to apply
     */
    public String postStep(HTTPRequest request) throws IOException;
}
