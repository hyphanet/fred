package freenet.clients.http.bookmark;

public abstract class Bookmark {
	protected String name;

	public String getName() {
		return name;
	}

	protected void setName(String s) {
		name = s;
	}
	
	public boolean equals(Object o) {
		if(o == this) return true;
		if(o instanceof Bookmark) {
			Bookmark b = (Bookmark) o;
			if(!b.name.equals(name)) return false;
			return true;
		} else return false;
	}
}
