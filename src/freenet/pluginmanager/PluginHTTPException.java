/*
  PluginHTTPException.java / Freenet
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.pluginmanager;

public class PluginHTTPException extends Exception {
	private static final long serialVersionUID = -1;
	
	private int code;
	private String mimeType;
	private String desc;
	private String reply;
	

	public PluginHTTPException () {
		this(404, "text/html", "FAIL", "Page not found");
	}
	public PluginHTTPException (int code, String mimeType, String desc, String reply) {
		this.code = code;
		this.mimeType = mimeType;
		this.desc = desc;
		this.reply = reply;
	}
	
	public void setCode(int code) {
		// FIXME: check!
		this.code = code;
	}
	public void setDesc(String desc) {
		this.desc = desc;
	}
	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}
	public void setReply(String reply) {
		this.reply = reply;
	}
	public int getCode() {
		return code;
	}
	public String getDesc() {
		return desc;
	}
	public String getMimeType() {
		return mimeType;
	}
	public String getReply() {
		return reply;
	}

}
