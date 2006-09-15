/*
  ExtOldAgeUserAlert.java / Freenet
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

public class ExtOldAgeUserAlert implements UserAlert {
	private boolean isValid=true;
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Freenet-ext too old";
	}
	
	public String getText() {
		String s;
		s = "Your freenet-ext.jar file seems to be outdated : we strongly advise you to update it using http://downloads.freenetproject.org/alpha/freenet-ext.jar.";
		return s;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", "Your freenet-ext.jar file seems to be outdated: we strongly advise you to update it using http://downloads.freenetproject.org/alpha/freenet-ext.jar.");
	}

	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return "Hide";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
