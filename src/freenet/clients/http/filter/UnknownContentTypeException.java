/*
  UnknownContentTypeException.java / Freenet
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

import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

public class UnknownContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	final String type;
	final String encodedType;
	
	public UnknownContentTypeException(String typeName) {
		this.type = typeName;
		encodedType = HTMLEncoder.encode(type);
	}
	
	public String getType() {
		return type;
	}

	public String getHTMLEncodedTitle() {
		return "Unknown and potentially dangerous content type: "+encodedType;
	}

	public String getRawTitle() {
		return "Unknown and potentially dangerous content type: "+type;
	}
	
	public String getExplanation() {
		return "<p>Your Freenet node does not know anything about this MIME type. " +
				"This means that your browser might do something dangerous in response " +
				"to downloading this file. For example, many formats can contain embedded images " +
				"or videos, which are downloaded from the web; this is by no means innocuous, " +
				"because they can ruin your anonymity and expose your IP address (if the attacker " +
				"runs the web site or has access to its logs). Hyperlinks to the Web can also be a " +
				"threat, for much the same reason, as can scripting, for this and other reasons.</p>";
	}
	
	public HTMLNode getHTMLExplanation() {
		return new HTMLNode("div", "Your Freenet node does not know anything about this MIME type. " +
				"This means that your browser might do something dangerous in response " +
				"to downloading this file. For example, many formats can contain embedded images " +
				"or videos, which are downloaded from the web; this is by no means innocuous, " +
				"because they can ruin your anonymity and expose your IP address (if the attacker " +
				"runs the web site or has access to its logs). Hyperlinks to the Web can also be a " +
				"threat, for much the same reason, as can scripting, for this and other reasons.");
	}
	
}
