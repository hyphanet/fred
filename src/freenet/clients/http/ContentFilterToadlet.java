package freenet.clients.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.FilterOperation;
import freenet.client.filter.UnsafeContentTypeException;
import freenet.client.filter.ContentFilter.FilterStatus;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.api.HTTPUploadedFile;
import freenet.support.io.Closer;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;

/**
 * Allows the user to run the content filter on a file and view the result.
 */
public class ContentFilterToadlet extends Toadlet implements LinkEnabledCallback {
    public final static String PATH = "/filterfile/";
    
    /**
     * What to do the the output from the content filter.
     */
    public static enum ResultHandling {
        DISPLAY,
        SAVE
    }
    
    private final NodeClientCore core;
    
    public ContentFilterToadlet(HighLevelSimpleClient client, NodeClientCore clientCore) {
        super(client);
        this.core = clientCore;
    }
    
    @Override
    public String path() {
        return PATH;
    }
    
    public boolean isEnabled (ToadletContext ctx) {
        boolean fullAccess = (!container.publicGatewayMode()) || ((ctx != null) && ctx.isAllowedFullAccess());
        return core.isAdvancedModeEnabled() && fullAccess;
    }

    public void handleMethodGET(URI uri, final HTTPRequest request, final ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        if (container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
            super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"),
                    NodeL10n.getBase().getString("Toadlet.unauthorized"));
            return;
        }
        
        PageMaker pageMaker = ctx.getPageMaker();
        
        PageNode page = pageMaker.getPageNode(l10n("pageTitle"), ctx);
        HTMLNode pageNode = page.outer;
        HTMLNode contentNode = page.content;

        contentNode.addChild(ctx.getAlertManager().createSummary());

        contentNode.addChild(createContent(pageMaker, ctx));
        
        writeHTMLReply(ctx, 200, "OK", null, pageNode.generate());
    }
    
    public void handleMethodPOST(URI uri, final HTTPRequest request, final ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        if (container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
            sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"),
                    NodeL10n.getBase().getString("Toadlet.unauthorized"));
            return;
        }
        try {
            // Browse... button on filter page
            if (request.isPartSet("filter-local")) {
                try {
                    FilterOperation filterOperation = getFilterOperation(request);
                    ResultHandling resultHandling = getResultHandling(request);
                    String mimeType = request.getPartAsStringFailsafe("mime-type", 100);
                    MultiValueTable<String, String> responseHeaders = new MultiValueTable<String, String>();
                    responseHeaders.put("Location", LocalFileFilterToadlet.PATH
                            + "?filter-operation=" + filterOperation
                            + "&result-handling=" + resultHandling
                            + "&mime-type=" + mimeType);
                    ctx.sendReplyHeaders(302, "Found", responseHeaders, null, 0);
                } catch (BadRequestException e) {
                    String invalidPart = e.getInvalidRequestPart();
                    if (invalidPart == "filter-operation") {
                        writeBadRequestError(l10n("errorMustSpecifyFilterOperationTitle"), l10n("errorMustSpecifyFilterOperation"), ctx, true);
                    } else if (invalidPart == "result-handling") {
                        writeBadRequestError(l10n("errorMustSpecifyResultHandlingTitle"), l10n("errorMustSpecifyResultHandling"), ctx, true);
                    } else {
                        writeBadRequestError(l10n("errorBadRequestTitle"), l10n("errorBadRequest"), ctx, true);
                    }
                    return;
                }
            // Filter button on local file browser
            } else if (request.isPartSet(LocalFileBrowserToadlet.selectFile)) {
                handleFilterRequest(request, ctx, core);
            // Filter File button on filter page
            } else if (request.isPartSet("filter-upload")) {
                handleUploadedFilterRequest(request, ctx, core);
            } else {
                handleMethodGET(uri, new HTTPRequestImpl(uri, "GET"), ctx);
            }
        } finally {
            request.freeParts();
        }
    }
    
    private HTMLNode createContent(PageMaker pageMaker, ToadletContext ctx) {
        InfoboxNode infobox = pageMaker.getInfobox(l10n("filterFile"), "filter-file", true);
        HTMLNode filterBox = infobox.outer;
        HTMLNode filterContent = infobox.content;

        HTMLNode filterForm = ctx.addFormChild(filterContent, PATH, "filterForm");
        
        // apply read filter, write filter, or both
        //TODO: radio buttons to select, once ContentFilter supports write filtering
        filterForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "hidden", "filter-operation", FilterOperation.BOTH.toString() });

        // display in browser or save to disk
        filterForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "radio", "result-handling", ResultHandling.DISPLAY.toString() });
        filterForm.addChild("#", l10n("displayResultLabel"));
        filterForm.addChild("br");
        filterForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "radio", "result-handling", ResultHandling.SAVE.toString() });
        filterForm.addChild("#", l10n("saveResultLabel"));
        filterForm.addChild("br");
        filterForm.addChild("br");
        
        // mime type
        filterForm.addChild("#", l10n("mimeTypeLabel") + ": ");
        filterForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "text", "mime-type", "" });
        filterForm.addChild("br");
        filterForm.addChild("#", l10n("mimeTypeText"));
        filterForm.addChild("br");
        filterForm.addChild("br");
        
        // file selection
        if (ctx.isAllowedFullAccess()) {
            filterForm.addChild("#", l10n("filterFileBrowseLabel") + ": ");
            filterForm.addChild("input",
                    new String[] { "type", "name", "value" },
                    new String[] { "submit", "filter-local", l10n("filterFileBrowseButton") + "..." });
            filterForm.addChild("br");
        }
        filterForm.addChild("#", l10n("filterFileUploadLabel") + ": ");
        filterForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "file", "filename", "" });
        filterForm.addChild("#", " \u00a0 ");
        filterForm.addChild("input", 
                new String[] { "type", "name", "value" },
                new String[] { "submit", "filter-upload", l10n("filterFileFilterLabel") });
        filterForm.addChild("#", " \u00a0 ");
        
        return filterBox;
    }

    private static void writeBadRequestError(String header, String message, ToadletContext context, boolean returnToFilterPage)
            throws ToadletContextClosedException, IOException {
        PageMaker pageMaker = context.getPageMaker();
        PageNode page = pageMaker.getPageNode(header, context);
        HTMLNode pageNode = page.outer;
        HTMLNode contentNode = page.content;
        if (context.isAllowedFullAccess()) {
            contentNode.addChild(context.getAlertManager().createSummary());
        }
        HTMLNode infoboxContent = pageMaker.getInfobox("infobox-error", header, contentNode, "filter-error", false);
        infoboxContent.addChild("#", message);
        if (returnToFilterPage) {
            NodeL10n.getBase().addL10nSubstitution(infoboxContent.addChild("div"),
                    "ContentFilterToadlet.tryAgainFilterFilePage", new String[] { "link" },
                    new HTMLNode[] { HTMLNode.link(ContentFilterToadlet.PATH) });
        }
        // copied from writeHTMLReply
        byte[] buffer = pageNode.generate().getBytes("UTF-8");
        context.sendReplyHeaders(400, "Bad request", null, "text/html; charset=utf-8", buffer.length);
        context.writeData(buffer, 0, buffer.length);
    }
    
    /**
     * Handle a request to filter a local file.
     */
    static void handleFilterRequest(HTTPRequest request, ToadletContext ctx, NodeClientCore core)
            throws ToadletContextClosedException, IOException {
        try {
            FilterOperation filterOperation = getFilterOperation(request);
            ResultHandling resultHandling = getResultHandling(request);
            String mimeType = request.getPartAsStringFailsafe("mime-type", 100);
            String filename = request.getPartAsStringFailsafe("filename", QueueToadlet.MAX_FILENAME_LENGTH);
            if (filename.length() == 0) {
                throw new BadRequestException("filename");
            }
            if (mimeType.length() == 0) {
                mimeType = DefaultMIMETypes.guessMIMEType(filename, false);
            }
            String resultFilename = makeResultFilename(filename, mimeType);
            File file = new File(filename);
            Bucket bucket = new FileBucket(file, true, false, false, false, false);
            try {
                handleFilter(bucket, mimeType, filterOperation, resultHandling, resultFilename, ctx, core);
            } catch (FileNotFoundException e) {
                writeBadRequestError(l10n("errorNoFileOrCannotReadTitle"), l10n("errorNoFileOrCannotRead", "file", filename), ctx, true);
                return;
            }
        } catch (BadRequestException e) {
            String invalidPart = e.getInvalidRequestPart();
            if (invalidPart == "filter-operation") {
                writeBadRequestError(l10n("errorMustSpecifyFilterOperationTitle"), l10n("errorMustSpecifyFilterOperation"), ctx, true);
            } else if (invalidPart == "result-handling") {
                writeBadRequestError(l10n("errorMustSpecifyResultHandlingTitle"), l10n("errorMustSpecifyResultHandling"), ctx, true);
            } else if (invalidPart == "filename") {
                writeBadRequestError(l10n("errorNoFileSelectedTitle"), l10n("errorNoFileSelected"), ctx, true);
            } else {
                writeBadRequestError(l10n("errorBadRequestTitle"), l10n("errorBadRequest"), ctx, true);
            }
        }
    }
    
    /**
     * Handle a request to filter a file which is contained in the request.
     */
    static void handleUploadedFilterRequest(HTTPRequest request, ToadletContext ctx, NodeClientCore core)
            throws ToadletContextClosedException, IOException {
        try {
            FilterOperation filterOperation = getFilterOperation(request);
            ResultHandling resultHandling = getResultHandling(request);
            String mimeType = request.getPartAsStringFailsafe("mime-type", 100);
            HTTPUploadedFile file = request.getUploadedFile("filename");
            if (file == null || file.getFilename().trim().length() == 0) {
                throw new BadRequestException("filename");
            }
            if (mimeType.length() == 0) {
                mimeType = file.getContentType();
            }
            String resultFilename = makeResultFilename(file.getFilename(), mimeType);
            handleFilter(file.getData(), mimeType, filterOperation, resultHandling, resultFilename, ctx, core);
        } catch (BadRequestException e) {
            String invalidPart = e.getInvalidRequestPart();
            if (invalidPart == "filter-operation") {
                writeBadRequestError(l10n("errorMustSpecifyFilterOperationTitle"), l10n("errorMustSpecifyFilterOperation"), ctx, true);
            } else if (invalidPart == "result-handling") {
                writeBadRequestError(l10n("errorMustSpecifyResultHandlingTitle"), l10n("errorMustSpecifyResultHandling"), ctx, true);
            } else if (invalidPart == "filename") {
                writeBadRequestError(l10n("errorNoFileSelectedTitle"), l10n("errorNoFileSelected"), ctx, true);
            } else {
                writeBadRequestError(l10n("errorBadRequestTitle"), l10n("errorBadRequest"), ctx, true);
            }
        }
    }
    
    private static FilterOperation getFilterOperation(HTTPRequest request)
            throws BadRequestException {
        String s = request.getPartAsStringFailsafe("filter-operation", 100);
        try {
            return FilterOperation.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("filter-operation", e);
        }
    }
    
    private static ResultHandling getResultHandling(HTTPRequest request)
            throws BadRequestException {
        String s = request.getPartAsStringFailsafe("result-handling", 100);
        try {
            return ResultHandling.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("result-handling", e);
        }
    }
    
    private static String makeResultFilename(String originalFilename, String mimeType) {
        String filteredFilename;
        int p = originalFilename.indexOf('.', 1);
        if (p > 0) {
            filteredFilename = originalFilename.substring(0, p) + ".filtered" + originalFilename.substring(p); 
        } else {
            filteredFilename = originalFilename + ".filtered";
        }
        filteredFilename = FileUtil.sanitize(filteredFilename, mimeType);
        return filteredFilename;
    }
    
    private static void handleFilter(Bucket data, String mimeType, FilterOperation operation, ResultHandling resultHandling, String resultFilename, ToadletContext ctx, NodeClientCore core)
            throws ToadletContextClosedException, IOException, BadRequestException {
        Bucket resultBucket = ctx.getBucketFactory().makeBucket(-1);
        String resultMimeType = null;
        boolean unsafe = false;
        try {
            FilterStatus status = applyFilter(data, resultBucket, mimeType, operation, core);
            resultMimeType = status.mimeType;
        } catch (UnsafeContentTypeException e) {
            unsafe = true;
        } catch (IOException e) {
            Logger.error(ContentFilterToadlet.class, "IO error running content filter", e);
            throw e;
        }

        if (unsafe) {
            // copied from Toadlet.sendErrorPage()
            PageNode page = ctx.getPageMaker().getPageNode(l10n("errorUnsafeContentTitle"), ctx);
            HTMLNode pageNode = page.outer;
            HTMLNode contentNode = page.content;
            
            HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", l10n("errorUnsafeContentTitle"), contentNode, null, true);
            infoboxContent.addChild(new HTMLNode("#", l10n("errorUnsafeContent")));
            infoboxContent.addChild("br");
            infoboxContent.addChild("a", "href", ".", NodeL10n.getBase().getString("Toadlet.returnToPrevPage"));
            infoboxContent.addChild("br");
            addHomepageLink(infoboxContent);
            
            byte[] buffer = pageNode.generate().getBytes("UTF-8");
            ctx.sendReplyHeaders(200, "OK", null, "text/html; charset=utf-8", buffer.length);
            ctx.writeData(buffer, 0, buffer.length);
        } else {
            if (resultHandling == ResultHandling.DISPLAY) {
                ctx.sendReplyHeaders(200, "OK", null, resultMimeType, resultBucket.size());
                ctx.writeData(resultBucket);
            } else if (resultHandling == ResultHandling.SAVE) {
                MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
                headers.put("Content-Disposition", "attachment; filename=\"" + resultFilename + '"');
                headers.put("Cache-Control", "private");
                headers.put("Content-Transfer-Encoding", "binary");
                ctx.sendReplyHeaders(200, "OK", headers, "application/force-download", resultBucket.size());
                ctx.writeData(resultBucket);
            } else {
                throw new BadRequestException("result-handling");
            }
        }
    }
    
    private static FilterStatus applyFilter(Bucket input, Bucket output, String mimeType, FilterOperation operation, NodeClientCore core)
            throws UnsafeContentTypeException, IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = input.getInputStream();
            outputStream = output.getOutputStream();
            return applyFilter(inputStream, outputStream, mimeType, operation, core);
        } finally {
            Closer.close(inputStream);
            Closer.close(outputStream);
        }
    }
    
    private static FilterStatus applyFilter(InputStream input, OutputStream output, String mimeType, FilterOperation operation, NodeClientCore core)
            throws UnsafeContentTypeException, IOException {
        URI fakeUri;
        try {
            fakeUri = new URI("http://127.0.0.1:8888/");
        } catch (URISyntaxException e) {
            Logger.error(ContentFilterToadlet.class, "Inexplicable URI error", e);
            return null;
        }
        //TODO: check operation, once ContentFilter supports write filtering
        return ContentFilter.filter(input, output, mimeType, fakeUri, null, null, null,
                core.getLinkFilterExceptionProvider());
    }
    
    static String l10n(String key) {
        return NodeL10n.getBase().getString("ContentFilterToadlet." + key);
    }
    
    static String l10n(String key, String pattern, String value) {
        return NodeL10n.getBase().getString("ContentFilterToadlet." + key, pattern, value);
    }
}
