package freenet.clients.http;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;

import freenet.clients.http.bookmark.Bookmark;
import freenet.clients.http.bookmark.BookmarkItem;
import freenet.clients.http.bookmark.BookmarkItems;
import freenet.clients.http.bookmark.BookmarkCategory;
import freenet.clients.http.bookmark.BookmarkCategories;
import freenet.clients.http.bookmark.BookmarkManager;

import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.client.HighLevelSimpleClient;
import freenet.support.HTMLNode;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.api.HTTPRequest;

public class BookmarkEditorToadlet extends Toadlet {

	private static final int MAX_ACTION_LENGTH = 20;
	private static final int MAX_KEY_LENGTH = QueueToadlet.MAX_KEY_LENGTH;
	private static final int MAX_NAME_LENGTH = 500;
	private static final int MAX_BOOKMARK_PATH_LENGTH = 10 * MAX_NAME_LENGTH;

	private final NodeClientCore core;
	private final BookmarkManager bookmarkManager;
	private String cutedPath;


	BookmarkEditorToadlet(HighLevelSimpleClient client, NodeClientCore core)
	{
		super(client);
		this.core = core;
		this.bookmarkManager = core.bookmarkManager;
		this.cutedPath = null;
	}

	private void addCategoryToList(BookmarkCategory cat, String path, HTMLNode list)
	{
		BookmarkItems items = cat.getItems();

		final String edit = L10n.getString("BookmarkEditorToadlet.edit");
		final String delete = L10n.getString("BookmarkEditorToadlet.delete");
		final String cut = L10n.getString("BookmarkEditorToadlet.cut");
		final String moveUp = L10n.getString("BookmarkEditorToadlet.moveUp");
		final String moveDown = L10n.getString("BookmarkEditorToadlet.moveDown");
		final String paste = L10n.getString("BookmarkEditorToadlet.paste");
		final String addBookmark = L10n.getString("BookmarkEditorToadlet.addBookmark");
		final String addCategory = L10n.getString("BookmarkEditorToadlet.addCategory");

		for(int i = 0; i < items.size(); i++) {

			String itemPath = URLEncoder.encode(path + items.get(i).getName());
			HTMLNode li = new HTMLNode("li", "class", "item" , items.get(i).getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");
			actions.addChild("a", "href", "?action=edit&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/edit.png", edit, edit});

			actions.addChild("a", "href", "?action=del&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/delete.png", delete, delete});

			if(cutedPath == null)
				actions.addChild("a", "href", "?action=cut&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/cut.png", cut, cut});

			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-up.png", moveUp, moveUp});

			if(i != items.size()-1)
				actions.addChild("a", "href", "?action=down&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-down.png", moveDown, moveDown});

			li.addChild(actions);
			list.addChild(li);
		}

		BookmarkCategories cats = cat.getSubCategories();
		for(int i = 0; i < cats.size(); i++) {

			String catPath = URLEncoder.encode(path + cats.get(i).getName() + "/");

			HTMLNode subCat = list.addChild("li", "class", "cat", cats.get(i).getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");

			actions.addChild("a", "href", "?action=edit&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/edit.png", edit, edit});

			actions.addChild("a", "href", "?action=del&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/delete.png", delete, delete});

			actions.addChild("a", "href", "?action=addItem&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/bookmark-new.png", addBookmark, addBookmark});

			actions.addChild("a", "href", "?action=addCat&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/folder-new.png", addCategory, addCategory});

			if(cutedPath == null)
				actions.addChild("a", "href", "?action=cut&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/cut.png", cut, cut});

			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-up.png", moveUp, moveUp});

			if(i != cats.size() -1)
				actions.addChild("a", "href", "?action=down&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-down.png", moveDown, moveDown});

			if(cutedPath != null && ! catPath.startsWith(cutedPath) && ! catPath.equals(bookmarkManager.parentPath(cutedPath)))
				actions.addChild("a", "href", "?action=paste&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/paste.png", paste, paste});

			subCat.addChild(actions);
			if(cats.get(i).size() != 0)
				addCategoryToList(cats.get(i), catPath, list.addChild("li").addChild("ul"));
		}
	}

	public HTMLNode getBookmarksList()
	{
		HTMLNode bookmarks = new HTMLNode("ul", "id", "bookmarks");

		HTMLNode root = bookmarks.addChild("li", "class", "cat root", "/");
		HTMLNode actions = new HTMLNode("span", "class", "actions");
		String addBookmark = L10n.getString("BookmarkEditorToadlet.addBookmark");
		String addCategory = L10n.getString("BookmarkEditorToadlet.addCategory");
		String paste = L10n.getString("BookmarkEditorToadlet.paste");
		actions.addChild("a", "href", "?action=addItem&bookmark=/").addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/bookmark-new.png", addBookmark, addBookmark});
		actions.addChild("a", "href", "?action=addCat&bookmark=/").addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/folder-new.png", addCategory, addCategory});

		if(cutedPath != null && ! "/".equals(bookmarkManager.parentPath(cutedPath)))
			actions.addChild("a", "href", "?action=paste&bookmark=/").addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/paste.png", paste, paste});

		root.addChild(actions);
		addCategoryToList(bookmarkManager.getMainCategory(), "/", root.addChild("ul"));

		return bookmarks;
	}

	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException 
	{

		String editorTitle = L10n.getString("BookmarkEditorToadlet.title");
		String error = L10n.getString("BookmarkEditorToadlet.error");
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(editorTitle, ctx);
		HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);

		if (req.getParam("action").length() > 0 && req.getParam("bookmark").length() > 0) {
			String action = req.getParam("action");
			String bookmarkPath;
			try {
				bookmarkPath = URLDecoder.decode(req.getParam("bookmark"), false);
			} catch (URLEncodedFormatException e) {
				HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", error));
				errorBox.addChild("#", L10n.getString("BookmarkEditorToadlet.urlDecodeError"));
				this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
				return;
			}
			Bookmark bookmark;

			if (bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);

			if(bookmark == null) {
				HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", error));
				errorBox.addChild("#", L10n.getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[] { "bookmark" }, new String[] { bookmarkPath }));
				this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
				return;
			} else {

				if(action.equals("del")){

					String[] bm = new String[] { "bookmark" };
					String[] path = new String[] { bookmarkPath };
					String queryTitle = L10n.getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmark" : "deleteCategory"));
					HTMLNode infoBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-query", queryTitle));

					String query = L10n.getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmarkConfirm" : "deleteCategoryConfirm"), bm, path);
					infoBox.addChild("p").addChild("#", query);

					HTMLNode confirmForm = ctx.addFormChild(infoBox.addChild("p"), "", "confirmDeleteForm");
					confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "bookmark", bookmarkPath});
					confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
					confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirmdelete", L10n.getString("BookmarkEditorToadlet.confirmDelete") });

				} else if (action.equals("cut")) {

					cutedPath = bookmarkPath;

				} else if ("paste".equals(action) && cutedPath != null) {

					bookmarkManager.moveBookmark(cutedPath, bookmarkPath, true);
					cutedPath = null;

				} else if (action.equals("edit") || action.equals("addItem") || action.equals("addCat")) {

					String header;
					if(action.equals("edit")) {
						header = L10n.getString("BookmarkEditorToadlet.edit" + ((bookmark instanceof BookmarkItem) ? "Bookmark" : "Category") + "Title");
					} else if(action.equals("addItem")) {
						header = L10n.getString("BookmarkEditorToadlet.addNewBookmark");
					} else {
						header = L10n.getString("BookmarkEditorToadlet.addNewCategory");
					}

					HTMLNode actionBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-query", header));

					HTMLNode form = ctx.addFormChild(actionBox, "", "editBookmarkForm");

					form.addChild("label", "for", "name", (L10n.getString("BookmarkEditorToadlet.nameLabel") + ' '));
					form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String []{"text", "name", "name", "20", action.equals("edit")?bookmark.getName():""});

					form.addChild("br");
					if ((action.equals("edit") && bookmark instanceof BookmarkItem) || action.equals("addItem")) {
						String key = (action.equals("edit") ? ((BookmarkItem) bookmark).getKey() : "");
						form.addChild("label", "for", "key", (L10n.getString("BookmarkEditorToadlet.keyLabel") + ' '));
						form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String []{"text", "key", "key", "50", key});
					}

					form.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "bookmark",bookmarkPath});

					form.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "action",req.getParam("action")});

					form.addChild("br");
					form.addChild("input", new String[]{"type", "value"}, new String[]{"submit", L10n.getString("BookmarkEditorToadlet.save")});
				} else if (action.equals("up") || action.equals("down")) {
					if(action.equals("up"))
						bookmarkManager.moveBookmarkUp(bookmarkPath, true);
					else
						bookmarkManager.moveBookmarkDown(bookmarkPath, true);
				}
			}

		}

		if(cutedPath != null) {
			HTMLNode infoBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.pasteTitle")));
			infoBox.addChild("#",L10n.getString("BookmarkEditorToadlet.pasteOrCancel"));
			HTMLNode cancelForm = ctx.addFormChild(infoBox.addChild("p"), "", "cancelCutForm");
			cancelForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancelCut", L10n.getString("BookmarkEditorToadlet.cancelCut") });
		}

		HTMLNode bookmarksBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.myBookmarksTitle")));
		bookmarksBox.addChild(getBookmarksList());

		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}


	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException 
	{
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(L10n.getString("BookmarkEditorToadlet.title"), ctx);
		HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);

		String passwd = req.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) 
			return;


		String bookmarkPath = req.getPartAsString("bookmark", MAX_BOOKMARK_PATH_LENGTH);
		try {

			Bookmark bookmark;
			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);
			if(bookmark == null) {
				HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", L10n.getString("BookmarkEditorToadlet.error")));
				errorBox.addChild("#", L10n.getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[] { "bookmark" } , new String[] { bookmarkPath }));
				return;
			}


			String action = req.getPartAsString("action", MAX_ACTION_LENGTH);

			if (req.isPartSet("confirmdelete")) {
				bookmarkManager.removeBookmark(bookmarkPath, true);
				HTMLNode successBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.deleteSucceededTitle")));
				successBox.addChild("p", L10n.getString("BookmarkEditorToadlet.deleteSucceeded"));

			} else if (req.isPartSet("cancelCut")) {
				cutedPath = null;

			} else if (action.equals("edit") || action.equals("addItem") || action.equals("addCat")) {

				String name = "unnamed";
				if (req.getPartAsString("name", MAX_NAME_LENGTH).length() > 0)
					name = req.getPartAsString("name", MAX_NAME_LENGTH);

				if(action.equals("edit")) {
					bookmarkManager.renameBookmark(bookmarkPath, name);
					if(bookmark instanceof BookmarkItem)
						((BookmarkItem) bookmark).setKey(new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH)));

					HTMLNode successBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.changesSavedTitle")));
					successBox.addChild("p", L10n.getString("BookmarkEditorToadlet.changesSaved"));

				} else if (action.equals("addItem") || action.equals("addCat")) {

					Bookmark newBookmark;
					if(action.equals("addItem")) {
						FreenetURI key = new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH));
						newBookmark = new BookmarkItem(key, name, core.alerts);
					} else
						newBookmark = new BookmarkCategory(name);

					bookmarkManager.addBookmark(bookmarkPath, newBookmark, true);

					HTMLNode successBox =  content.addChild(ctx.getPageMaker().getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.addedNewBookmarkTitle")));
					successBox.addChild("p", L10n.getString("BookmarkEditorToadlet.addedNewBookmark"));
				}
			}
		} catch (MalformedURLException mue) {
			HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", L10n.getString("BookmarkEditorToadlet.invalidKeyTitle")));
			errorBox.addChild("#", L10n.getString("BookmarkEditorToadlet.invalidKey"));
		}
		HTMLNode bookmarksBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.myBookmarksTitle")));
		bookmarksBox.addChild(getBookmarksList());

		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}

	public String supportedMethods()
	{
		return "GET, POST";
	}
}
