/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.async.ManifestElement;

import freenet.keys.FreenetURI;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

//~--- JDK imports ------------------------------------------------------------

import java.net.MalformedURLException;

public class RedirectDirPutFile extends DirPutFile {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {
            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    final FreenetURI targetURI;

    public RedirectDirPutFile(String name, String mimeType, FreenetURI targetURI) {
        super(name, mimeType);
        this.targetURI = targetURI;
    }

    public static RedirectDirPutFile create(String name, String contentTypeOverride, SimpleFieldSet subset,
            String identifier, boolean global)
            throws MessageInvalidException {
        String target = subset.get("TargetURI");

        if (target == null) {
            throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD,
                                              "TargetURI missing but UploadFrom=redirect", identifier, global);
        }

        FreenetURI targetURI;

        try {
            targetURI = new FreenetURI(target);
        } catch (MalformedURLException e) {
            throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: " + e,
                                              identifier, global);
        }

        if (logMINOR) {
            Logger.minor(RedirectDirPutFile.class, "targetURI = " + targetURI);
        }

        String mimeType;

        if (contentTypeOverride != null) {
            mimeType = contentTypeOverride;
        } else {
            mimeType = guessMIME(name);
        }

        return new RedirectDirPutFile(name, mimeType, targetURI);
    }

    @Override
    public Bucket getData() {
        return null;
    }

    @Override
    public ManifestElement getElement() {
        return new ManifestElement(name, targetURI, getMIMEType());
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        container.activate(targetURI, 5);
        targetURI.removeFrom(container);
        container.delete(this);
    }
}
