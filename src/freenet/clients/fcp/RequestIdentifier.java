package freenet.clients.fcp;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Identifies a request and its client. Stable serialization format - must not change without
 * version bump, must maintain back compatibility within reason. This is initially used to check 
 * for whether we've already loaded a request, but it will also be used for last-resort restarting
 * a request when serialization has failed.
 * @author toad
 */
public final class RequestIdentifier {
    
    enum RequestType {
        // Ordinals matter!
        GET,
        PUT,
        PUTDIR
    }
    
    static final int MAGIC = 0x25ebd38d;
    static final short VERSION = 1;
    
    final boolean globalQueue;
    final String clientName;
    final String identifier;
    public final RequestType type;
    
    public RequestIdentifier(boolean globalQueue, String clientName, String identifier, 
            RequestType type) {
        this.globalQueue = globalQueue;
        this.clientName = clientName;
        this.identifier = identifier;
        this.type = type;
    }
    
    public RequestIdentifier(DataInput dis) throws IOException {
        int magic = dis.readInt();
        if(magic != MAGIC) throw new IOException("Bad magic");
        short version = dis.readShort();
        if(version != VERSION) throw new IOException("Bad version");
        this.globalQueue = dis.readBoolean();
        if(globalQueue)
            clientName = null;
        else
            clientName = dis.readUTF();
        identifier = dis.readUTF();
        RequestType[] types = RequestType.values();
        short typeKey = dis.readShort();
        if(typeKey < 0 || typeKey >= types.length) throw new IOException("Bogus type");
        type = types[typeKey];
    }
    
    public void writeTo(DataOutput dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeShort(VERSION);
        dos.writeBoolean(globalQueue);
        if(!globalQueue)
            dos.writeUTF(clientName);
        dos.writeUTF(identifier);
        dos.writeShort(type.ordinal());
    }
    
    /** Only compare the identifier, not the type. */
    public boolean sameIdentifier(RequestIdentifier other) {
        if(globalQueue != other.globalQueue) return false;
        if(!globalQueue) {
            if(!clientName.equals(other.clientName)) return false;
        }
        if (globalQueue != other.globalQueue)
            return false;
        if(!identifier.equals(other.identifier)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((clientName == null) ? 0 : clientName.hashCode());
        result = prime * result + (globalQueue ? 1231 : 1237);
        result = prime * result + ((identifier == null) ? 0 : identifier.hashCode());
        // Intentionally don't include the type at all.
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if(!(obj instanceof RequestIdentifier))
            return false;
        RequestIdentifier other = (RequestIdentifier) obj;
        if(globalQueue != other.globalQueue) return false;
        if(!globalQueue) {
            if(!clientName.equals(other.clientName)) return false;
        }
        if (globalQueue != other.globalQueue)
            return false;
        if(!identifier.equals(other.identifier)) return false;
        if (type != other.type)
            return false;
        return true;
    }
}