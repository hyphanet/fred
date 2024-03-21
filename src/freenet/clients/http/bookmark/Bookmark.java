package freenet.clients.http.bookmark;

import freenet.l10n.NodeL10n;
import freenet.support.SimpleFieldSet;

public abstract class Bookmark {

	protected String name;

	public String getName() {
		return name;
	}

	public String getVisibleName() {
		if(name.toLowerCase().startsWith("l10n:"))
			return NodeL10n.getBase().getString("Bookmarks.Defaults.Name."+name.substring("l10n:".length()));
		return name;
	}

	protected void setName(String s) {
		name = (s.length() > 0 ? s : NodeL10n.getBase().getString("Bookmark.noName"));
	}

	@Override
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

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	public abstract SimpleFieldSet getSimpleFieldSet();
}
