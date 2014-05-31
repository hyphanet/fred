/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.useralerts;

//~--- non-JDK imports --------------------------------------------------------

import freenet.l10n.NodeL10n;

import freenet.support.HTMLNode;

public class SimpleHTMLUserAlert extends AbstractUserAlert {
    public SimpleHTMLUserAlert(boolean canDismiss, String title, String shortText, HTMLNode content, short type) {
        super(canDismiss, title, content.getContent(), shortText, content, type, true,
              NodeL10n.getBase().getString("UserAlert.hide"), true, null);
    }

    @Override
    public void isValid(boolean validity) {

        // Do nothing
    }
}
