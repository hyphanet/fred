package freenet.clients.http.bookmark;

import java.util.Vector;
import java.util.Iterator;

import freenet.support.StringArray;


public class BookmarkCategory extends Bookmark	// implements Iterator
{

  private final Vector bookmarks;

  public BookmarkCategory (String name) {
    bookmarks = new Vector ();
    setName (name);
  } public BookmarkCategory (String name, String desc) {
    bookmarks = new Vector ();
    setName (name);
    setDesc (desc);
  }

  protected Bookmark addBookmark (Bookmark b) {
    bookmarks.add (b);
    return b;
  }

  protected void removeBookmark (Bookmark b) {
    bookmarks.remove (b);
  }

  public Bookmark get (int i) {
    return (Bookmark) bookmarks.get (i);
  }

  protected void moveBookmarkUp (Bookmark b) {
    int index = bookmarks.indexOf (b);
    if (index == -1)
      return;

    Bookmark bk = get (index);
    bookmarks.remove (index);
    bookmarks.add ((--index < 0) ? 0 : index, bk);
  }

  protected void moveBookmarkDown (Bookmark b) {
    int index = bookmarks.indexOf (b);
    if (index == -1)
      return;

    Bookmark bk = get (index);
    bookmarks.remove (index);
    bookmarks.add ((++index > size ())? size () : index, bk);
  }

  public int size () {
    return bookmarks.size ();
  }

  public BookmarkItems getItems () {
    BookmarkItems items = new BookmarkItems ();
    for (int i = 0; i < size (); i++) {
      if (get (i) instanceof BookmarkItem)
	items.add ((BookmarkItem) get (i));
    }

    return items;
  }

  public BookmarkItems getAllItems () {
    BookmarkItems items = getItems ();
    BookmarkCategories subCategories = getSubCategories ();

    for (int i = 0; i < subCategories.size (); i++) {
      items.extend (subCategories.get (i).getAllItems ());
    }
    return items;
  }


  public BookmarkCategories getSubCategories () {
    BookmarkCategories categories = new BookmarkCategories ();
    for (int i = 0; i < size (); i++) {
      if (get (i) instanceof BookmarkCategory)
	categories.add ((BookmarkCategory) get (i));
    }

    return categories;
  }

  public BookmarkCategories getAllSubCategories () {
    BookmarkCategories categories = getSubCategories ();
    BookmarkCategories subCategories = getSubCategories ();

    for (int i = 0; i < subCategories.size (); i++) {
      categories.extend (subCategories.get (i).getAllSubCategories ());
    }

    return categories;
  }


  public String[] toStrings () {
    return StringArray.toArray (toStrings ("").toArray ());
  }

  // Iternal use only
  private Vector toStrings (String prefix) {
    Vector strings = new Vector ();
    BookmarkItems items = getItems ();
    BookmarkCategories subCategories = getSubCategories ();
    prefix += this.name + "/";

    for (int i = 0; i < items.size (); i++)
      strings.add (prefix + items.get (i).toString ());

    for (int i = 0; i < subCategories.size (); i++)
      strings.addAll (subCategories.get (i).toStrings (prefix));

    return strings;

  }

  public void setPrivate (boolean bool) {
    privateBookmark = bool;

    BookmarkCategories subCategories = getSubCategories ();
    for (int i = 0; i < size (); i++)
      subCategories.get (i).setPrivate (bool);
  }

  public Iterator iterator () {
    return bookmarks.iterator ();
  }

}
