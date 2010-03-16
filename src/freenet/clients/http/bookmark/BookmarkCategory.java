package freenet.clients.http.bookmark;

import java.util.List;
import java.util.Vector;

import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;

public class BookmarkCategory extends Bookmark {
	public static final String NAME = "BookmarkCategory";

	private final Vector bookmarks = new Vector();

	public BookmarkCategory(String name) {
		setName(name);
	}

	public BookmarkCategory(SimpleFieldSet sfs) throws FSParseException {
	String aName = sfs.get("Name");
	if(aName == null) throw new FSParseException("No Name!");
	setName(aName);
	}

	protected synchronized Bookmark addBookmark(Bookmark b) {
		if (b == null) {
			return null;
		}
	// Overwrite any existing bookmark
		int x = bookmarks.indexOf(b);
		if (x >= 0) {
			return (Bookmark) bookmarks.get(x);
		}
		bookmarks.add(b);
		return b;
	}

	protected synchronized void removeBookmark(Bookmark b) {
		bookmarks.remove(b);
	}

	public Bookmark get(int i) {
		return (Bookmark) bookmarks.get(i);
	}

	protected void moveBookmarkUp(Bookmark b) {
		int index = bookmarks.indexOf(b);
		if (index == -1) {
			return;
		}

		Bookmark bk = get(index);
		bookmarks.remove(index);
		bookmarks.add((--index < 0) ? 0 : index, bk);
	}

	protected void moveBookmarkDown(Bookmark b) {
		int index = bookmarks.indexOf(b);
		if (index == -1) {
			return;
		}

		Bookmark bk = get(index);
		bookmarks.remove(index);
		bookmarks.add((++index > size()) ? size() : index, bk);
	}

	public int size() {
		return bookmarks.size();
	}

	public List<BookmarkItem> getItems() {
		List<BookmarkItem>  items = new Vector<BookmarkItem>();
		for (int i = 0; i < size(); i++) {
			if (get(i) instanceof BookmarkItem) {
				items.add((BookmarkItem) get(i));
			}
		}

		return items;
	}

	public List<BookmarkItem> getAllItems() {
		List<BookmarkItem> items = getItems();
		List<BookmarkCategory> subCategories = getSubCategories();

		for (int i = 0; i < subCategories.size(); i++) {
			items.addAll(subCategories.get(i).getAllItems());
		}
		return items;
	}

	public List<BookmarkCategory> getSubCategories() {
		List<BookmarkCategory> categories = new Vector<BookmarkCategory>();
		for (int i = 0; i < size(); i++) {
			if (get(i) instanceof BookmarkCategory) {
				categories.add((BookmarkCategory) get(i));
			}
		}

		return categories;
	}

	public List<BookmarkCategory> getAllSubCategories() {
		List<BookmarkCategory> categories = getSubCategories();
		List<BookmarkCategory> subCategories = getSubCategories();

		for (int i = 0; i < subCategories.size(); i++) {
			categories.addAll(subCategories.get(i).getAllSubCategories());
		}

		return categories;
	}

	public String[] toStrings() {
		return toStrings("").toArray(new String[0]);
	}

	// Internal use only

	private Vector<String> toStrings(String prefix) {
		Vector<String> strings = new Vector<String>();
		List<BookmarkItem> items = getItems();
		List<BookmarkCategory> subCategories = getSubCategories();
		prefix += this.name + "/";

		for (int i = 0; i < items.size(); i++) {
			strings.add(prefix + items.get(i).toString());
		}

		for (int i = 0; i < subCategories.size(); i++) {
			strings.addAll(subCategories.get(i).toStrings(prefix));
		}

		return strings;

	}

	@Override
	public SimpleFieldSet getSimpleFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
	sfs.putSingle("Name", name);
	sfs.put("Content", BookmarkManager.toSimpleFieldSet(this));
		return sfs;
	}
	// Don't override equals(), two categories are equal if they have the same name and description.

}
