/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;
import freenet.support.io.NativeThread;

/**
 *
 * @author nextgens
 */
public class NotEnoughNiceLevelsUserAlert extends AbstractUserAlert {
	public NotEnoughNiceLevelsUserAlert() {
		super(true, null, null, null, UserAlert.WARNING, true, L10n.getString("UserAlert.hide"), true, null);
	}
	
	public String getTitle() {
		return L10n.getString("NotEnoughNiceLevelsUserAlert.title");
	}
	
	public String getText() {
		return L10n.getString("NotEnoughNiceLevelsUserAlert.content",
			new String[] { "available", "required" },
			new String[] { 
				String.valueOf(NativeThread.NATIVE_PRIORITY_RANGE),
				String.valueOf(NativeThread.JAVA_PRIORITY_RANGE) 
			});
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

}
