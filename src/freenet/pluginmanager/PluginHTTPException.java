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
