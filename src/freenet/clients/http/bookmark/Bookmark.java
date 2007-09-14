package freenet.clients.http.bookmark;

public abstract class Bookmark {
	protected boolean privateBookmark;

	protected String name;

	protected String desc;

	public boolean isPrivate() {
		return privateBookmark;
	}

	public abstract void setPrivate(boolean bool);

	public String getName() {
		return name;
	}

	protected void setName(String s) {
		name = s;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String s) {
		desc = s;
	}
	
	public boolean equals(Object o) {
		if(o instanceof Bookmark) {
			Bookmark b = (Bookmark) o;
			if(!b.name.equals(name)) return false;
			if(!b.desc.equals(desc)) return false;
			if(b.privateBookmark != privateBookmark) return false;
			return true;
		} else return false;
	}
}
