package freenet.clients.http.bookmark;

import java.util.Vector;
import java.util.Iterator;

public final class BookmarkCategories // implements Iterator
{

	Vector categories;

	public BookmarkCategories() {
		categories = new Vector();
	}

	public BookmarkCategory get(int i) {
		return (BookmarkCategory) categories.get(i);
	}

	public void add(BookmarkCategory bc) {
		categories.add(bc);
	}

	protected void extend(BookmarkCategories bc) {
		for (int i = 0; i < bc.size(); i++)
			add(bc.get(i));
	}

	public int size() {
		return categories.size();
	}

	public Iterator iterator() {
		return categories.iterator();
	}
}
