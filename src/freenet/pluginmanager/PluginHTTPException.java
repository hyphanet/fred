/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.MultiValueTable;

public class PluginHTTPException extends Exception {
	private static final long serialVersionUID = -1;
	
	private int code;
	private String mimeType;
	private String desc;
	private String reply;
	private MultiValueTable headers = null;

	public PluginHTTPException () {
		this(404, "text/html", "FAIL", "Page not found");
	}
	public PluginHTTPException (int code, String mimeType, String desc, String reply) {
		this.code = code;
		this.mimeType = mimeType;
		this.desc = desc;
		this.reply = reply;
	}

	public PluginHTTPException (int code, String desc, MultiValueTable headers, String mimeType, String reply) {
		this.code = code;
		this.desc = desc;
		this.headers = headers;
		this.mimeType = mimeType;
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
	public MultiValueTable getHeaders() {
		return headers;
	}
}
