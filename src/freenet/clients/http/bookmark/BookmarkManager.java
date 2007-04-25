/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */


package freenet.clients.http.bookmark;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import freenet.client.async.USKCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.support.api.StringArrCallback;


public class BookmarkManager {

  private final NodeClientCore node;
  private USKUpdatedCallback uskcb;
  private boolean started;
  private BookmarkCategory mainCategory;
  private HashMap bookmarks;
  private SubConfig sc;

  public BookmarkManager (NodeClientCore n, SubConfig sc) {

    bookmarks = new HashMap ();
    mainCategory = new BookmarkCategory ("/");
    bookmarks.put ("/", mainCategory);

    this.uskcb = new USKUpdatedCallback ();
    this.node = n;
    this.sc = sc;

    try {

      BookmarkCategory defaultRoot = new BookmarkCategory ("/");

      BookmarkCategory indexes =
	(BookmarkCategory) defaultRoot.
	addBookmark (new BookmarkCategory ("Indexes"));
        indexes.
	addBookmark (new
		     BookmarkItem (new
				   FreenetURI
				   ("USK@7H66rhYmxIFgMyw5Dl11JazXGHPhp7dSN7WMa1pbtEo,jQHUQUPTkeRcjmjgrc7t5cDRdDkK3uKkrSzuw5CO9uk,AQACAAE/ENTRY.POINT/20/full/page1.html"),
				   "Entry point (freesites with descriptions but no categories)", node.alerts));

        indexes.
    	addBookmark (new
    		     BookmarkItem (new
    				   FreenetURI
    				   ("USK@e4TEIN5l1nkn6kjl63XBgYTYobmwGvtnyK2YW0b0ajo,hv-2~OfetXkb0FhDuPxorWIf0wXeZKPEfdIhwyh-mhk,AQABAAE/AnotherIndex/106/"),
    				   "Another Index (freesites with categories but no descriptions)", node.alerts));
        
        indexes.
    	addBookmark (new
    		     BookmarkItem (new
    				   FreenetURI
    				   ("USK@BPZppy07RyID~NGihHgs4AAw3fUXxgtKIrwRu5rtpWE,k5yjkAFJC93JkydKl6vpY0Zy9D8ec1ymv2XP4Tx5Io0,AQABAAE/FreeHoo/6/"),
    				   "Free Hoo (very old freesite reviews site)", node.alerts));
        
      BookmarkCategory flog =
	(BookmarkCategory) defaultRoot.
	addBookmark (new BookmarkCategory ("Freenet devel's flogs"));
        flog.
	addBookmark (new
		     BookmarkItem (new
				   FreenetURI
				   ("USK@J585KtAJ7UN2~4i17hf7C9XbufMnticJeUDYLcB0dvo,lxZhX2snsExxemocIlI~ZJRFVdVLBLIFZhqV3yswR9U,AQABAAE/toad/10/"),
				   "Toad", node.alerts));
        flog.
	addBookmark (new
		     BookmarkItem (new
				   FreenetURI
				   ("USK@hM9XRwjXIzU8xTSBXNZvTn2KuvTSRFnVn4EER9FQnpM,gsth24O7ud4gL4NwNuYJDUqfaWASOG2zxZY~ChtgPxc,AQACAAE/Flog/2/"),
				   "Nextgen$", node.alerts));

      BookmarkCategory apps =
	(BookmarkCategory) defaultRoot.
	addBookmark (new BookmarkCategory ("Freenet related software"));
        apps.
	addBookmark (new
		     BookmarkItem (new
				   FreenetURI
				   ("USK@XeMBryjuEaxqazEuxwnn~G7wCUOXFOZlVWbscdCOUFs,209eycYVidlZvhgL5V2a3INFxrofxzQctEZvyJaFL7I,AQABAAE/frost/2/"),
				   "Frost", node.alerts));

        sc.register ("bookmarks", defaultRoot.toStrings (), 0, true, false,
		     "List of bookmarks", "A list of bookmarked freesites",
		     makeCB ());

      if (!importOldBookmarks ())
	  makeCB ().
	  set ((sc.getStringArr ("bookmarks").length ==
		0 ? defaultRoot.toStrings () : sc.
		getStringArr ("bookmarks")));


    }
    catch (MalformedURLException mue) {
      // just ignore that one
    }
    catch (InvalidConfigValueException icve) {
      //TODO
      icve.printStackTrace ();
    }

    synchronized (this) {
      started = true;
    }
  }

  public class BookmarkCallback implements StringArrCallback {
    private final Pattern pattern =
      Pattern.compile ("/(.*/)([^/]*)=([A-Z]{3}@.*).*");
    public String[] get () {

      synchronized (BookmarkManager.this) {

	return mainCategory.toStrings ();

      }
    } public void set (String[]newVals) throws InvalidConfigValueException {
      clear ();

      FreenetURI key;
      for (int i = 0; i < newVals.length; i++) {
	try {
	  Matcher matcher = pattern.matcher (newVals[i]);
	  if (matcher.matches () && matcher.groupCount () == 3) {

	    makeParents (matcher.group (1));
	    key = new FreenetURI (matcher.group (3));
	    addBookmark (matcher.group (1),
			 new BookmarkItem (key, matcher.group (2),
					   node.alerts), false);

	  }
	  else
	      throw new InvalidConfigValueException ("Malformed Bookmark");

	}
	catch (MalformedURLException mue) {
	  throw new InvalidConfigValueException (mue.getMessage ());
	}
      }
    }
  }

  private class USKUpdatedCallback implements USKCallback {
    public void onFoundEdition (long edition, USK key) {
      BookmarkItems items = mainCategory.getAllItems ();
      for (int i = 0; i < items.size (); i++) {


	if (!items.get (i).getKeyType ().equals ("USK"))
	  continue;

	try {
	  FreenetURI furi = new FreenetURI (items.get (i).getKey ());
	  USK usk = USK.create (furi);

	  if (usk.equals (key, false)) {
	    items.get (i).setEdition (key.suggestedEdition, node);
	    break;
	  }
	} catch (MalformedURLException mue) {
	}
      }
      node.storeConfig ();
    }
  }

  private boolean importOldBookmarks () {
    String[]strs = sc.getStringArr ("bookmarks");

    final Pattern pattern = Pattern.compile ("([A-Z]{3}@.*)=(.*)");
    for (int i = 0; i < strs.length; i++) {
      Matcher matcher = pattern.matcher (strs[i]);
      if (matcher.matches () && matcher.groupCount () == 2) {
	      if(getCategoryByPath("/Imported/") == null)
		       addBookmark ("/",new BookmarkCategory("Imported"), false);
	try {
	  addBookmark ("/Imported/",
		       new BookmarkItem (new FreenetURI (matcher.group (1)),
					 matcher.group (2), node.alerts),
		       false);
	}
	catch (MalformedURLException mue) {	}
      }
      else
	return false;
    }

    node.storeConfig ();
    return true;
  }

  public BookmarkCallback makeCB () {
    return new BookmarkCallback ();
  }

  public BookmarkCategory getMainCategory () {
    return mainCategory;
  }

  public String parentPath (String path) {
    if (path.equals ("/"))
      return "/";

    return path.substring (0,
			   path.substring (0,
					   path.length () -
					   1).lastIndexOf ("/")) + "/";
  }

  public Bookmark getBookmarkByPath (String path) {
    return (Bookmark) bookmarks.get (path);
  }

  public BookmarkCategory getCategoryByPath (String path) {
    if (getBookmarkByPath (path) instanceof BookmarkCategory)
      return (BookmarkCategory) getBookmarkByPath (path);

    return null;
  }

  public BookmarkItem getItemByPath (String path) {
    if (getBookmarkByPath (path) instanceof BookmarkItem)
      return (BookmarkItem) getBookmarkByPath (path);

    return null;
  }

  public void addBookmark (String parentPath, Bookmark b,
			   boolean store) throws NullPointerException {
    BookmarkCategory parent = getCategoryByPath (parentPath);
    if (parent == null)
      throw new NullPointerException ();
    else {
      parent.addBookmark (b);
      bookmarks.put (parentPath + b.getName () +
		     ((b instanceof BookmarkCategory) ? "/" : ""), b);
    }
    if (store)
        node.storeConfig ();
  }

  // TODO
  public void renameBookmark (String path, String newName) {
    Bookmark bookmark = getBookmarkByPath (path);
    bookmark.setName (newName);
    if (bookmark instanceof BookmarkCategory) {
      try {
	makeCB ().set (makeCB ().get ());

      }
      catch (InvalidConfigValueException icve) {
      }
    }

  }

  public void removeBookmark (String path, boolean store) {
    Bookmark bookmark = getBookmarkByPath (path);
    if (bookmark == null)
      return;

    if (bookmark instanceof BookmarkCategory) {
      BookmarkCategory cat = (BookmarkCategory) bookmark;
      for (int i = 0; i < cat.size (); i++) {
	removeBookmark (path + cat.get (i).getName () +
			((cat.
			  get (i) instanceof BookmarkCategory) ? "/" : ""),
			false);
      }
    }
    else {
      if (((BookmarkItem) bookmark).getKeyType ().equals ("USK")) {
	try {
	  USK u = ((BookmarkItem) bookmark).getUSK ();
	  this.node.uskManager.subscribe (u, this.uskcb, true, this);
	}
	catch (MalformedURLException mue) {
	}
      }
    }

    getCategoryByPath (parentPath (path)).
      removeBookmark (getBookmarkByPath (path));
    bookmarks.remove (path);

    if (store)
      node.storeConfig ();

  }

  public void moveBookmarkUp (String path, boolean store) {
    BookmarkCategory parent = getCategoryByPath (parentPath (path));
    parent.moveBookmarkUp (getBookmarkByPath (path));

    if (store)
      node.storeConfig ();
  }

  public void moveBookmarkDown (String path, boolean store) {
    BookmarkCategory parent = getCategoryByPath (parentPath (path));
    parent.moveBookmarkDown (getBookmarkByPath (path));

    if (store)
      node.storeConfig ();
  }

  private BookmarkCategory makeParents (String path) {
    if (bookmarks.containsKey (path))
      return getCategoryByPath (path);
    else {

      int index = path.substring (0, path.length () - 1).lastIndexOf ("/");
      String name = path.substring (index + 1, path.length () - 1);

      BookmarkCategory cat = new BookmarkCategory (name);
      makeParents (parentPath (path));
      addBookmark (parentPath (path), cat, false);

      return cat;
    }
  }

  public void clear () {

    removeBookmark ("/", false);
    bookmarks.clear ();

    mainCategory = new BookmarkCategory ("/");
    bookmarks.put ("/", mainCategory);

  }

  public FreenetURI[] getBookmarkURIs () {
    BookmarkItems items = mainCategory.getAllItems ();
    FreenetURI[]uris = new FreenetURI[items.size ()];
    for (int i = 0; i < items.size (); i++) {
      uris[i] = items.get (i).getURI ();
    }

    return uris;
  }



/*
	public void addBookmark(Bookmark b, boolean store) {
		this.bookmarks.add(b);
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = b.getUSK();
				this.node.uskManager.subscribe(u, this.uskcb, true, this);
			} catch (MalformedURLException mue) {
				
			}
		}
		if(store && started) node.storeConfig();
	}
	
	public void removeBookmark(Bookmark b, boolean store) {
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = b.getUSK();
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
			
			}
		}
		this.bookmarks.remove(b);
		if(store && started) node.storeConfig();
	}
	
	public void moveBookmarkDown (Bookmark b, boolean store) {		
		int i = this.bookmarks.indexOf(b);
		if (i == -1) return;
		
		Bookmark bk = (Bookmark)this.bookmarks.get(i);
		this.bookmarks.remove(i);
		this.bookmarks.add((i+1)%(this.bookmarks.size()+1), bk);
		
		if(store && started) node.storeConfig();
	}

	public void moveBookmarkUp (Bookmark b, boolean store) {
		int i = this.bookmarks.indexOf(b);
		if (i == -1) return;
		
		Bookmark bk = (Bookmark)this.bookmarks.get(i);
		this.bookmarks.remove(i);
		if (--i < 0) i = this.bookmarks.size();
		this.bookmarks.add(i, bk);
		
		if(store && started) node.storeConfig();
	}
	
	public int getSize() {
		return this.bookmarks.size();
	}*/
}
