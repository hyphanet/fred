/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;

/**
 * This class would verify whether the BMP header is valid or not
 Reference:
 http://www.fastgraph.com/help/bmp_header_format.html
 http://en.wikipedia.org/wiki/BMP_file_format
 */
public class BMPFilter implements ContentDataFilter {

	
	static final byte[] bmpHeaderwindows =
		{ (byte)'B', (byte)'M'};
	
		static final byte[] bmpHeaderos2bArray =
		{ (byte)'B', (byte)'A'};

		static final byte[] bmpHeaderos2cIcon =
		{ (byte)'C', (byte)'I'};


		static final byte[] bmpHeaderos2cPointer =
		{ (byte)'C', (byte)'P'};


		static final byte[] bmpHeaderos2Icon =
		{ (byte)'I', (byte)'C'};


		static final byte[] bmpHeaderos2Pointer =
		{ (byte)'P', (byte)'T'};


    private int unsignedByte(byte b)
    {
        if (b >= 0)
            return b;
        else
            return 256+b;
    }


  public int readInt(DataInputStream dis) throws IOException
  {
    int result;
    byte[] data = new byte[4];

     result = dis.read(data);
     if (result < 0) // end of file reached
         throw new EOFException();

     result = (unsignedByte(data[2]) << 16) | (unsignedByte(data[1]) << 8) | unsignedByte(data[0]);
     result|=(unsignedByte(data[3]) << 24);

     return result;
  }


     public int readShort(DataInputStream dis) throws IOException
    {
        int result = dis.read();
        if (result < 0)// end of file reached
            throw new EOFException();

        int r2 = is.read();
        if (r2 < 0)// end of file reached
            throw new EOFException();

        return result | (r2*256);
    }

	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		if(data.size() < 54) { // Size of the bmp header is 54
			throwHeaderError(l10n("Too short file"), l10n("The file is too short to contain a bmp header"));
		}
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is);
		DataInputStream dis = new DataInputStream(bis);
		try {
			
		byte[] StartWord = new byte[2];
		dis.readFully(StartWord);
		if((!Arrays.equals(StartWord, bmpHeaderwindows)) && (!Arrays.equals(StartWord, bmpHeaderos2bArray)) && (!Arrays.equals(StartWord, bmpHeaderos2cIcon)) && (!Arrays.equals(StartWord, bmpHeaderos2cPointer)) && (!Arrays.equals(StartWord, bmpHeaderos2Icon)) && (!Arrays.equals(StartWord, bmpHeaderos2Pointer))) {	//Checking the first word
				throwHeaderError(l10n("Invalid start word"), l10n("invalidHeader"));
		}
			
			
			
		int fileSize = readInt(dis); // read total file size
		byte[] skipbytes=new byte[4];
		dis.readFully(skipbytes);
        int headerSize = readInt(dis); // read file header size or pixel offset


		int size_bitmapinfoheader=readInt(dis);
		if(size_bitmapinfoheader!=40) {
					throwHeaderError(l10n("Invalid Bit Map info header size"), l10n("Size of bitmap info header is not 40"));
		}


        int imageWidth = readInt(dis); // read width
        int imageHeight = readInt(dis); // read height
		if(imageWidth<0 || imageHeight<0) {
					throwHeaderError(l10n("Invalid Dimensions"), l10n("The image has invalid width or height"));
		}


        
		int no_plane=readShort(dis);
		if(no_plane<0) {
					throwHeaderError(l10n("Invalid no of plannes"), l10n("The image has "+no_plane+" planes"));
		}
				

        int bitDepth = readShort(dis);
		if(bitDepth<0) {
					throwHeaderError(l10n("Invalid bit depth"), l10n("The bit depth field is set to"+bitDepth));
		}

		int compression_type=readInt(dis);
		if( !(compression_type>=0 && compression_type<=3) ) {
					throwHeaderError(l10n("Invalid Compression type"), l10n("Compression type field is set to "+compression_type+" instead of 0-3"));
		}
			
		int imagedatasize=readInt(dis);
		if(fileSize!=headerSize+imagedatasize) {
					throwHeaderError(l10n("Invalid File size"), l10n("File size is not matching to headersize+ imagedatasize"));
		}
			
		System.out.println("filesize="+fileSize+" headerByteCount="+headerSize+" width="+imageWidth+" height="+imageHeight+" bitDepth="+bitDepth+" compressionType="+compression_type+" bytesOfPixelData="+imagedatasize);

			dis.close();
		} finally {
			Closer.close(dis);
		}
		return data;
	}

	private static String l10n(String key) {
		return L10n.getString("BMPFilter."+key);
	}

	private void throwHeaderError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = l10n("notBMP");
		if(reason != null) message += ' ' + reason;
		if(shortReason != null)
			message += " - (" + shortReason + ')';
		throw new DataFilterException(shortReason, shortReason,
				"<p>"+message+"</p>", new HTMLNode("p").addChild("#", message));
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		return null;
	}

}
