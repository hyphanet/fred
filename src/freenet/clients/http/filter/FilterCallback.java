/*
  FilterCallback.java / Freenet
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

package freenet.clients.http.filter;

/**
 * Callback to be provided to a content filter.
 */
public interface FilterCallback {

	/**
	 * Process a URI.
	 * If it cannot be turned into something sufficiently safe, then return null.
	 * @param overrideType Force the return type.
	 */
	public String processURI(String uri, String overrideType);

	/**
	 * Should we allow GET forms?
	 */
	public boolean allowGetForms();
	
	/**
	 * Should we allow POST forms?
	 */
	public boolean allowPostForms();

	/**
	 * Process a base URI in the page. Not only is this filtered, it affects all
	 * relative uri's on the page.
	 */
	public String onBaseHref(String baseHref);
	
	/**
	 * Process plain-text. Notification only; can't modify.
	 * Type can be null, or can correspond, for example to HTML tag name around text
	 *    (for example: "title")
	 */
	public void onText(String s, String type);
	
}
