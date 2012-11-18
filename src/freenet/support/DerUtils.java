/*
 * Low-level manipulation with DER objects.
 *
 * (c) 2012 Eleriseth <Eleriseth@WPECGLtYbVi8Rl6Y7Vzl2Lvd2EUVW99v3yNV3IWROG8>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import java.util.Arrays;

public class DerUtils {
	protected DerUtils() {
		throw new Error("Instance of "+DerUtils.class+" forbidden");
	}
	/** Extracts length of DER object from buffer.
	 * @param data buffer
	 * @param offset start of DER object in buffer (0 based).
	 * @return offset + length of DER object
	 */
	public static int DerLength(byte[] data, int offset)
	{
		offset++;
		byte b0 = data[offset++];
		int i0 = b0 & 0x7f;
		if ((b0 & (byte)0x80)==(byte)0) return offset + i0;
		int value = 0;
		if(i0 == 0) throw new IllegalArgumentException("The indefinite form is illegal in DER");
		// i0 is length
		if (data[offset] == (byte)0) throw new IllegalArgumentException("Length must have minimal encoding in DER");
		if (i0 == 1 && data[offset] > (byte)0) throw new IllegalArgumentException("Length must have minimal encoding in DER");
		while (i0-- > 0) {
			if(value >= (Integer.MAX_VALUE>>8)) throw new IllegalArgumentException("Integer oveflow in reading DER length");
			value = (value << 8) | (data[offset++] & 0xFF);
		}
		if(value > data.length - offset) throw new ArrayIndexOutOfBoundsException("DER object length is outside of buffer: "+value+">"+(data.length - offset));
		return offset + value;
	}

	/** Extracts offset of DER object payload.
	 * Payload starts from DerInnerOffset(data, offset) and ends at DerLength(data, offset).
	 * @param data buffer.
	 * @param offset start of DER object in buffer (0 based).
	 * @return position of payload
	 *
	 * To extract DER object from data buffer:
	 *    byte object_type = data[offset];
	 *    int endpos = DerLength(offset);
	 *    byte[] payload = Arrays.copyOfRange(data, DerInnerOffset(data, offset), endpos);
	 * At this moment, `payload' is DER object payload and `endpos' is position in buffer after object;
	 */
	public static int DerInnerOffset(byte [] data, int offset)
	{
		offset++;
		byte b0 = data[offset++];
		int i0 = b0 & 0x7f;
		if ((b0 & (byte)0x80)==(byte)0) return offset;
		if(i0 == 0) throw new IllegalArgumentException("The indefinite form is illegal in DER");
		return offset + i0;
	}

	/** Calculates length of `encoded length' field for DER object.
	 * Complete object will use (1 + DerLengthLength(len) + len) bytes.
	 * @param len length of object content
	 * @return length of encoded length field
	 */
	public static int DerLengthLength(int len)
	{
		int derLen = 1;
		if(len < 0) throw new IllegalArgumentException("DER object length must be positive");
		if (len < 0x7f) return derLen;
		while(len > 0) {
			derLen ++;
			len >>= 8;
		}
		assert(derLen <= 0x7f);
		return derLen;
	}
	/** encodes DER object length in buffer.
	 * @param offset start of DER object in buffer
	 * @param len length of DER object payload
	 * @return position of object payload
	 * To encode object payload:
	 *   data[offset] = (byte)object_type;
	 *   offset = DerEncodeLength(data, offset, payload.length)
	 *   System.arraycopy(payload, 0, data, offset, payload.length);
	 *   offset += payload.length;
	 * At this point, offset points to first byte after DER object
	 */
	public static int DerEncodeLength(byte [] data, int offset, int len)
	{
		offset ++;
		if (len < 0) throw new IllegalArgumentException("DER object length must be positive");
		if (len < 0x7f) { data[offset++] = (byte)len; return offset; }
		int derLen = DerLengthLength(len);
		data[offset] = (byte)(derLen|0x80);
		int end = offset + derLen;
		while(--derLen > 0) {
			data[offset+derLen] = (byte)len;
			len >>= 8;
		}
		return end;
	}

	// EC Pubkey: Uncompressed:
	//     0:d=0  hl=2 l=  89 cons: SEQUENCE          
	//     2:d=1  hl=2 l=  19 cons: SEQUENCE          
	//     4:d=2  hl=2 l=   7 prim: OBJECT            :id-ecPublicKey
	//    13:d=2  hl=2 l=   8 prim: OBJECT            :prime256v1
	//    23:d=1  hl=2 l=  66 prim: BIT STRING        
	// EC Pubkey: Compressed:
	//    0:d=0  hl=2 l=  57 cons: SEQUENCE          
	//    2:d=1  hl=2 l=  19 cons: SEQUENCE          
	//    4:d=2  hl=2 l=   7 prim: OBJECT            :id-ecPublicKey
	//    13:d=2  hl=2 l=   8 prim: OBJECT            :prime256v1
	//    23:d=1  hl=2 l=  34 prim: BIT STRING        

	public final static byte[] DerECPubkeyFromRAW(byte[] header, byte [] raw)
	{
		return DerECPubkeyFromRAW(header, raw, false);
	}
	/** Adds header to raw pubkey and encode it as DER.
	 * @param fromDER should be true if raw is DER-encoded BITSTRING object and false if it is raw pubkey
	 * @throws Exception if pubkey was not in expected format
	 */
	public final static byte[] DerECPubkeyFromRAW(byte[] header, byte [] raw, boolean fromDER)
	{
		return DerECPubkeyFromRAW(header, raw, 0, raw.length, fromDER);
	}
	public static byte[] DerECPubkeyFromRAW(byte[] header, byte [] raw, int offset, int length, boolean fromDER)
	{
		if(fromDER) {
			if(raw[offset] != (byte)0x03) // BITSTRING
				throw new IllegalArgumentException("RAW pubkey is expected to be single DER-encoded BITSTRING");
			if(DerLength(raw, offset) != length + offset)
				throw new IllegalArgumentException("RAW pubkey is expected to be single DER-encoded BITSTRING");
		}
		int innerLength = length + (fromDER ? 0 : (DerLengthLength(length) + 1)) + header.length;
		byte[] data = new byte [1 + DerLengthLength(innerLength) + innerLength];
		int off= 0;
		data[off] = 0x30; // SEQUENCE
		off = DerEncodeLength(data, off, innerLength);
		System.arraycopy(header, 0, data, off, header.length);
		off += header.length;
		if (!fromDER) {
			data[off] = 0x03; // BITSTRING
			off = DerEncodeLength(data, off, length);
		}
		System.arraycopy(raw, offset, data, off, length);
		return data;
	}
	public static byte [] DerECPubkeyToRAW(byte [] header, byte[] pubkey)
	{
		return DerECPubkeyToRAW(header, pubkey, false);
	}
	/** Strip fixed header from DER-encoded EC pubkey and convert it to raw format.
	 * If @param asDer is true, convert to DER-encoded BITSTRING object.
	 * @throws Exception if pubkey was not in expected format
	 */
	public static byte [] DerECPubkeyToRAW(byte [] header, byte[] pubkey, boolean asDer)
	{
		return DerECPubkeyToRAW(header, pubkey, 0, asDer);
	}
	public static byte [] DerECPubkeyToRAW(byte [] header, byte[] pubkey, int offset)
	{
		return DerECPubkeyToRAW(header, pubkey, offset, false);
	}
	public static byte [] DerECPubkeyToRAW(byte [] header, byte[] pubkey, int offset, boolean asDER)
	{
		if(pubkey[0] != 0x30) // SEQUENCE
			throw new IllegalArgumentException("EC pubkey expected to be DER SEQUENCE");
		int outerLength = DerLength(pubkey, offset);
		int outerOffset = DerInnerOffset(pubkey, offset);
		if (!Fields.byteArrayEqual(pubkey, header, outerOffset, 0, header.length))
			throw new IllegalArgumentException("EC pubkey expected to start from fixed header");
		if(DerLength(header, 0) != header.length)
			throw new IllegalArgumentException("Fixed pubkey header should be single DER object");
		int innerOffset = outerOffset+header.length;
		if(pubkey[innerOffset] != 0x03) // BITSTRING
			throw new IllegalArgumentException("EC pubkey payload expected to be DER BITSTRING");
		int innerLength = DerLength(pubkey, innerOffset);
		if(pubkey.length != innerLength)
			throw new IllegalArgumentException("EC pubkey payload expected to be last element of object");
		if(!asDER) innerOffset = DerInnerOffset(pubkey, innerOffset);
		byte [] raw = Arrays.copyOfRange(pubkey, innerOffset, innerLength);
		return raw;
	}

	public static byte[] DerECSignatureToCVC(byte [] data) {
		return DerECSignatureToCVC(data, -1);
	}
	/** Convert ECDSA signature in DER format into sigSize raw encoding.
	 * Use variable-size encoding if sigSize &lt; 0
	 * P256 - sigSize = 64
	 * P384 - sigSize = 96
	 * P521 - sigSize = 132
	 *
	 * @return byte[sigSize] with converted signature
	 */
	public static byte[] DerECSignatureToCVC(byte [] data, int sigSize) {
		byte[] signature = sigSize < 0 ? null : new byte[sigSize];
		sigSize = DerECSignatureToCVC(data, sigSize, signature, 0);
		if (signature == null)
			DerECSignatureToCVC(data, sigSize, signature, 0);
		return signature;
	}

	/** Convert ECDSA signature in DER format to CVC format.
	 * @param sigSize if sigSize &gt;= 0, pad signature to this size.
	 *        otherwise, use variable-size encoding
	 * @param out if out is not null, encodes signature starting from
	 * @param outOffset
	 *        otherwise, just verify format and return signature length
	 * @return length of signature
	 * @throws Exception if signature is not in expected format
	 */
	public static int DerECSignatureToCVC(byte [] data, int sigSize,
			byte [] out, int outOffset) {
		int offset = 0;
		if(data[offset] != (byte)0x30)
			throw new IllegalArgumentException("Expect DER SEQUENCE");
		int end = DerLength(data, offset);
		if(offset + end != data.length)
			throw new IllegalArgumentException("Expect single DER object");
		int inner = DerInnerOffset(data, offset);

		if(data[inner] != (byte)0x02)
			throw new IllegalArgumentException("Expect DER INTEGER (r)");
		int rEnd = DerLength(data, inner);
		int rPos = DerInnerOffset(data, inner);
		if(rEnd+2 >= end)
			throw new ArrayIndexOutOfBoundsException("DER INTEGER out of SEQUENCE size");

		if(data[rEnd] != (byte)0x02)
			throw new IllegalArgumentException("Expect DER INTEGER (s)");
		int sEnd = DerLength(data, rEnd);
		int sPos = DerInnerOffset(data, rEnd);
		if(sEnd != end)
			throw new IllegalArgumentException("Expected SEQUENCE of two INTEGER, found tailer");
		if(rPos < rEnd && data[rPos] < (byte)0x00)
			throw new IllegalArgumentException("Expected non-negative INTEGER");
		while(rPos < rEnd && data[rPos] == (byte)0x00) rPos++;
		if(sPos < sEnd && data[sPos] < (byte)0x00)
			throw new IllegalArgumentException("Expected non-negative INTEGER");
		while(sPos < sEnd && data[sPos] == (byte)0x00) sPos++;

		if (sigSize < 0) {
			sigSize = Math.max(rEnd - rPos, sEnd - sPos)*2;
		} else {
			assert(sigSize % 2 == 0);
			assert(Math.max(rEnd - rPos, sEnd - sPos)*2 <= sigSize);
		}
		if (out == null)
			return sigSize;
		assert(out.length - outOffset >= sigSize);
		int intSize = sigSize/2;
		if(rEnd - rPos <= intSize)
			System.arraycopy(data, rPos,
					out, outOffset+intSize-(rEnd-rPos), rEnd-rPos);
		else
			throw new IllegalArgumentException("Expected at most "+intSize+" byte integer, got "+(rEnd-rPos));

		if(sEnd - sPos <= intSize)
			System.arraycopy(data, sPos, 
					out, outOffset+intSize+intSize-(sEnd-sPos), sEnd-sPos);
		else
			throw new IllegalArgumentException("expected at most "+intSize+" byte integer, got "+(sEnd-sPos));
		return sigSize;
	}

	/** Convert EC signature from CVC format to DER.
	 */
	public static byte[] DerECSignatureFromCVC(byte[] signature, int sigSize)
	{
		return DerECSignatureFromCVC(signature, 0, sigSize);
	}
	public static byte[] DerECSignatureFromCVC(byte[] data, int inOffset, int sigSize)
	{
		assert(data.length-inOffset >= sigSize);
		assert(sigSize % 2 == 0);
		int intSize = sigSize/2;
        int rPos = inOffset;
        int rEnd = rPos+intSize;
        while(rPos < rEnd && data[rPos] == (byte)0) rPos++;
		int rLength = rEnd-rPos;
		if(rPos < rEnd && data[rPos] < (byte)0) rLength++;
        int sPos = rEnd;
        int sEnd = sPos+intSize;
        while(sPos < sEnd && data[sPos] == (byte)0) sPos++;
		int sLength = sEnd-sPos;
		if(sPos < sEnd && data[sPos] < (byte)0) sLength++;
		int int1len = 1+DerLengthLength(rLength)+rLength;
		int int2len = 1+DerLengthLength(sLength)+sLength;
		int seqlen = 1+DerLengthLength(int1len+int2len)+int1len+int2len;
		byte[] out = new byte[seqlen];
		int offset = 0;
		out[offset] = (byte)0x30; // SEQUENCE
		offset = DerEncodeLength(out, offset, int1len+int2len);
		out[offset] = (byte)0x02; // INTEGER
		offset = DerEncodeLength(out, offset, rLength);
		System.arraycopy(data, rPos, out, offset+(rLength-(rEnd-rPos)), rEnd-rPos);
		offset += rLength;
		out[offset] = (byte)0x02; // INTEGER
		offset = DerEncodeLength(out, offset, sLength);
		System.arraycopy(data, sPos, out, offset+(sLength-(sEnd-sPos)), sEnd-sPos);
		offset += sLength;
		assert(offset == out.length);
		return out;
	}
}
