package freenet.clients.http.bookmark;

import freenet.l10n.L10n;
import freenet.support.SimpleFieldSet;

public abstract class Bookmark {

	protected String name;

	public String getName() {
		return name;
	}

	protected void setName(String s) {
		name = (s.length() > 0 ? s : L10n.getString("Bookmark.noName"));
	}

	public boolean equals(Object o) {
		if(o == this)
			return true;
		if(o instanceof Bookmark) {
			Bookmark b = (Bookmark) o;
			if(!b.name.equals(name))
				return false;
			return true;
		} else
			return false;
	}

	public int hashCode() {
		return name.hashCode();
	}

	public abstract SimpleFieldSet getSimpleFieldSet();
}
