/*
  UserAlert.java / Freenet
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

public interface UserAlert {
	
	/**
	 * Can the user dismiss the alert?
	 * If not, it persists until it is unregistered.
	 */
	public boolean userCanDismiss();
	
	/**
	 * Title of alert (must be short!).
	 */
	public String getTitle();
	
	/**
	 * Content of alert (plain text).
	 */
	public String getText();

	/**
	 * Content of alert (HTML).
	 */
	public HTMLNode getHTMLText();
	
	/**
	 * Priority class
	 */
	public short getPriorityClass();
	
	/**
	 * Is the alert valid right now? Suggested use is to synchronize on the
	 * alert, then check this, then get the data.
	 */
	public boolean isValid();
	
	public void isValid(boolean validity);
	
	public String dismissButtonText();
	
	public boolean shouldUnregisterOnDismiss();
	
	/**
	 * Method to be called upon alert dismissal
	 */
	public void onDismiss();
	
	/** An error which prevents normal operation */
	public final static short CRITICAL_ERROR = 0;
	/** An error which prevents normal operation but might be temporary */
	public final static short ERROR = 1;
	/** An error; limited anonymity due to not enough connections, for example */
	public final static short WARNING = 2;
	/** Something minor */
	public final static short MINOR = 3;
}
