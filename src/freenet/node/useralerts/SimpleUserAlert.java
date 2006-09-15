/*
  SimpleUserAlert.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class SimpleUserAlert implements UserAlert {

	final boolean canDismiss;
	final String title;
	final String text;
	final short type;
	
	public SimpleUserAlert(boolean canDismiss, String title, String text, short type) {
		this.canDismiss = canDismiss;
		this.title = title;
		this.text = text;
		this.type = type;
	}

	public boolean userCanDismiss() {
		return canDismiss;
	}

	public String getTitle() {
		return title;
	}

	public String getText() {
		return text;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", text);
	}

	public short getPriorityClass() {
		return type;
	}

	public boolean isValid() {
		return true;
	}

	public void isValid(boolean validity) {
		// Do nothing
	}

	public String dismissButtonText() {
		return "Hide";
	}

	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
