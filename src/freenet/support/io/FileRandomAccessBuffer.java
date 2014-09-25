package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.LockableRandomAccessBuffer;

public class FileRandomAccessBuffer implements LockableRandomAccessBuffer, Serializable {

    private static final long serialVersionUID = 1L;
    transient RandomAccessFile raf;
	final File file;
	private boolean closed = false;
	private final long length;
	private final boolean readOnly;
	private boolean secureDelete;
	
	public FileRandomAccessBuffer(RandomAccessFile raf, File filename, boolean readOnly) throws IOException {
		this.raf = raf;
		this.file = filename;
		length = raf.length();
        this.readOnly = readOnly;
	}
	
    public FileRandomAccessBuffer(File filename, long length, boolean readOnly) throws IOException {
        raf = new RandomAccessFile(filename, readOnly ? "r" : "rw");
        raf.setLength(length);
        this.length = length;
        this.file = filename;
        this.readOnly = readOnly;
    }

    public FileRandomAccessBuffer(File filename, boolean readOnly) throws IOException {
        raf = new RandomAccessFile(filename, readOnly ? "r" : "rw");
        this.length = raf.length();
        this.file = filename;
        this.readOnly = readOnly;
    }

    @Override
	public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
	    if(fileOffset < 0) throw new IllegalArgumentException();
        if(fileOffset + length > this.length)
            throw new IOException("Length limit exceeded reading "+length+" bytes from "+fileOffset+" of "+this.length);
        // FIXME Use NIO (which has proper pread, with concurrency)! This is absurd!
		synchronized(this) {
			raf.seek(fileOffset);
			raf.readFully(buf, bufOffset, length);
		}
	}

	@Override
	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
        if(fileOffset < 0) throw new IllegalArgumentException();
	    if(fileOffset + length > this.length)
            throw new IOException("Length limit exceeded writing "+length+" bytes from "+fileOffset+" of "+this.length);
	    if(readOnly) throw new IOException("Read only");
        // FIXME Use NIO (which has proper pwrite, with concurrency)! This is absurd!
		synchronized(this) {
			raf.seek(fileOffset);
			raf.write(buf, bufOffset, length);
		}
	}

	@Override
	public long size() {
	    return length;
	}

	@Override
	public void close() {
		synchronized(this) {
			if(closed) return;
			closed = true;
		}
		try {
			raf.close();
		} catch (IOException e) {
			Logger.error(this, "Could not close "+raf+" : "+e+" for "+this, e);
		}
	}

    @Override
    public RAFLock lockOpen() {
        return new RAFLock() {

            @Override
            protected void innerUnlock() {
                // Do nothing. RAFW is always open.
            }
            
        };
    }

    @Override
    public void free() {
        close();
        if(secureDelete) {
            try {
                FileUtil.secureDelete(file);
            } catch (IOException e) {
                Logger.error(this, "Unable to delete "+file+" : "+e, e);
                System.err.println("Unable to delete temporary file "+file);
            }
        } else {
            file.delete();
        }
    }
    
    public void setSecureDelete(boolean secureDelete) {
        this.secureDelete = secureDelete;
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        if(!file.exists()) throw new ResumeFailedException("File does not exist any more");
        if(file.length() != length) throw new ResumeFailedException("File is wrong length");
        try {
            raf = new RandomAccessFile(file, readOnly ? "r" : "rw");
        } catch (FileNotFoundException e) {
            throw new ResumeFailedException("File does not exist any more");
        }
    }
    
    static final int MAGIC = 0xdd0f4ab2;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeUTF(file.toString());
        dos.writeBoolean(readOnly);
        dos.writeLong(length);
        dos.writeBoolean(secureDelete);
    }

    public FileRandomAccessBuffer(DataInputStream dis) throws IOException, StorageFormatException, ResumeFailedException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        file = new File(dis.readUTF());
        readOnly = dis.readBoolean();
        length = dis.readLong();
        secureDelete = dis.readBoolean();
        if(length < 0) throw new StorageFormatException("Bad length");
        // Have to check here because we need the RAF immediately.
        if(!file.exists()) throw new ResumeFailedException("File does not exist");
        if(length > file.length()) throw new ResumeFailedException("Bad length");
        this.raf = new RandomAccessFile(file, readOnly ? "r" : "rw");
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((file == null) ? 0 : file.hashCode());
        result = prime * result + (int) (length ^ (length >>> 32));
        result = prime * result + (readOnly ? 1231 : 1237);
        result = prime * result + (secureDelete ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        FileRandomAccessBuffer other = (FileRandomAccessBuffer) obj;
        if (!file.equals(other.file)) {
            return false;
        }
        if (length != other.length) {
            return false;
        }
        if (readOnly != other.readOnly) {
            return false;
        }
        if (secureDelete != other.secureDelete) {
            return false;
        }
        return true;
    }

}
