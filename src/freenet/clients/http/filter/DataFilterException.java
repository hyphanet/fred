/*
  DataFilterException.java / Freenet
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

import freenet.support.HTMLNode;

/**
 * Exception thrown when the data cannot be filtered.
 */
public class DataFilterException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;

	final String rawTitle;
	final String encodedTitle;
	final String explanation;
	final HTMLNode htmlExplanation;
	
	DataFilterException(String raw, String encoded, String explanation, HTMLNode htmlExplanation) {
		this.rawTitle = raw;
		this.encodedTitle = encoded;
		this.explanation = explanation;
		this.htmlExplanation = htmlExplanation;
	}
	
	public String getExplanation() {
		return explanation;
	}
	
	public HTMLNode getHTMLExplanation() {
		return htmlExplanation;
	}

	public String getHTMLEncodedTitle() {
		return encodedTitle;
	}

	public String getRawTitle() {
		return rawTitle;
	}

}
