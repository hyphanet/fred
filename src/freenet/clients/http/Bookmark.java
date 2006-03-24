package freenet.clients.http;

import freenet.keys.FreenetURI;
import java.net.MalformedURLException;

public class Bookmark {
		FreenetURI key;
		String desc;
		
		Bookmark(String k, String d) throws MalformedURLException {
			this.key = new FreenetURI(k);
			this.desc = d;
		}
		
		Bookmark(String from) throws MalformedURLException {
			int eqpos = from.indexOf("=");
			
			if (eqpos < 0) {
				this.key = new FreenetURI(from);
				this.desc = from;
			} else {
				this.key = new FreenetURI(from.substring(0, eqpos));
				this.desc = from.substring(eqpos + 1);
			}
		}
		
		public String getKey() {
			return key.toString();
		}
		
		public void setKey(FreenetURI uri) {
			key = uri;
		}
		
		public String getKeyType() {
			return key.getKeyType();
		}
		
		public String getDesc() {
			if (desc == "") {
				return "Unnamed Bookmark";
			} else {
				return desc;
			}
		}
		
		public String toString() {
			return this.key.toString() + "=" + this.desc;
		}
	}