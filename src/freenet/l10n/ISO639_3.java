/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import freenet.support.io.Closer;

/**
 * Provides the content of the ISO639-3 standard for language codes.
 * Description of what this standard is (taken from http://www.sil.org/iso639-3/default.asp):
 * 
 * "ISO 639-3 is a code that aims to define three-letter identifiers for all known human languages.
 * At the core of ISO 639-3 are the individual languages already accounted for in ISO 639-2.
 * The large number of living languages in the initial inventory of ISO 639-3 beyond those already
 * included in ISO 639-2 was derived primarily from Ethnologue (15th edition).
 * Additional extinct, ancient, historic, and constructed languages have been obtained from Linguist List."
 * 
 * Source of the code tables in here:
 * http://www.sil.org/iso639-3/iso-639-3_20100707.tab
 * 
 * @author xor (xor@freenetproject.org)
 *
 */
public final class ISO639_3 {

	/**
	 * A class which represents a language code. It was translated from the example SQL-table-definition on
	 * http://www.sil.org/iso639-3/download.asp
	 * 
	 * The quoted texts on the JavaDoc of the member variables are the original comments from the SQL-table-defintion.
	 */
	public static final class LanguageCode {
		/**
		 * "The three-letter 639-3 identifier", 3 characters, not null.
		 */
		public final String id;
		
		/**
		 * "Equivalent 639-2 identifier of the bibliographic applications code set, if there is one", 3 characters, may be null.
		 * 
		 */
		public final String part2B;

		/**
		 * "Equivalent 639-2 identifier of the terminology applications code set, if there is one", 3 characters, may be null. 
		 */
		public final String part2T;
		 
		/**
		 * "Equivalent 639-1 identifier, if there is one", 2 characters, may be null.
		 */
		public final String part1;

		static enum Scope {
			Individual,
			Macrolanguage,
			Special;
			
			public static Scope fromTabFile(String abbreviation) {
				if(abbreviation.equals("I")) return Scope.Individual;
				else if(abbreviation.equals("M")) return Scope.Macrolanguage;
				else if(abbreviation.equals("S")) return Scope.Special;
				else throw new IllegalArgumentException("Unknown scope abbreviation: " + abbreviation);
			}
		};
		
		/**
		 * The scope of the language, never null.
		 */
		public final Scope scope;

		static enum Type {
			Ancient,
			Constructed,
			Extinct,
			Historical,
			Living,
			Special;
			
			public static Type fromTabFile(String abbreviation) {
				if(abbreviation.equals("A")) return Type.Ancient;
				else if(abbreviation.equals("C")) return Type.Constructed;
				else if(abbreviation.equals("E")) return Type.Extinct;
				else if(abbreviation.equals("H")) return Type.Historical;
				else if(abbreviation.equals("L")) return Type.Living;
				else if(abbreviation.equals("S")) return Type.Special;
				else throw new IllegalArgumentException("Unknwon type abbreviation: " + abbreviation); 
			}
		}
		
		/**
		 * The type of the language, never null.
		 */
		public final Type type;

		/**
		 * "Reference language name", never null.
		 */
		public final String referenceName;
		

		/**
		 * "Comment relating to one or more of the columns", may be null.
		 */
		public final String comment;
		
		
		private LanguageCode(char[] myId, char[] myPart2B, char[] myPart2T, char[] myPart1, Scope myScope, Type myType,
				String myReferenceName, String myComment) {
			
			if(myId == null) throw new NullPointerException();
			if(myId.length > 3) throw new IllegalArgumentException();
			if(myPart2B != null && myPart2B.length > 3) throw new IllegalArgumentException();
			if(myPart2T != null && myPart2T.length > 3) throw new IllegalArgumentException();
			if(myPart1 != null && myPart1.length > 2) throw new IllegalArgumentException();
			if(myScope == null) throw new NullPointerException();
			if(myType == null) throw new NullPointerException();
			if(myReferenceName == null) throw new NullPointerException();
			if(myReferenceName.length() > 150) throw new IllegalArgumentException();
			if(myComment != null && myComment.length() > 150) throw new IllegalArgumentException();
			
			id = new String(myId).toLowerCase();
			part2B = new String(myPart2B).toLowerCase();
			part2T = new String(myPart2T).toLowerCase();
			part1 = new String(myPart1).toLowerCase();
			scope = myScope;
			type = myType;
			referenceName = myReferenceName;
			comment = myComment;
		}
		
		public boolean equals(LanguageCode other) {
			return id.equals(other.id);
		}
		
		public boolean equals(Object o) {
			if(!(o instanceof LanguageCode))
				return false;
			
			return equals((LanguageCode)o);
		}
		
		public String toString() {
			return new String(id) + " = " + referenceName + " (type: " + type + ")";
		}
	}
	
	private static final Set<LanguageCode> loadFromTabFile() {
		final HashSet<LanguageCode> codes = new HashSet<LanguageCode>(7705 * 2);

		InputStream in = null;
		InputStreamReader isr = null;
		BufferedReader br = null;
		
		try {
			// Returns null on lookup failures:
			in = ISO639_3.class.getClassLoader().getResourceAsStream("freenet/l10n/iso-639-3_20100707.tab");
			
			if (in == null)
				throw new RuntimeException("Could not open the language codes resource");
			
			isr = new InputStreamReader(in, "UTF-8");
			br = new BufferedReader(isr);
			
			{
				String[] headerTokens = br.readLine().split("[\t]");
				if(
						!headerTokens[0].equals("﻿Id")
						|| !headerTokens[1].equals("Part2B")
						|| !headerTokens[2].equals("Part2T")
						|| !headerTokens[3].equals("Part1")
						|| !headerTokens[4].equals("Scope")
						|| !headerTokens[5].equals("Language_Type")
						|| !headerTokens[6].equals("Ref_Name")
						|| !headerTokens[7].equals("Comment")
				)
					throw new RuntimeException("File header does not match the expected header.");
			}
		
			for(String line = br.readLine(); line != null; line = br.readLine()) {
				line = line.trim();
				if(line.length() == 0)
					continue;
				
				final String[] tokens = line.split("[\t]");
				
				if(tokens.length != 8 && tokens.length != 7)
					throw new RuntimeException("Line with invalid token amount: " + line);
				
				final LanguageCode newCode = new LanguageCode(
						tokens[0].toCharArray(),
						tokens[1].toCharArray(),
						tokens[2].toCharArray(), 
						tokens[3].toCharArray(),
						LanguageCode.Scope.fromTabFile(tokens[4]),
						LanguageCode.Type.fromTabFile(tokens[5]),
						tokens[6],
						tokens.length==8 ? tokens[7] : null
						);
				
				if(!codes.add(newCode))
					throw new RuntimeException("Duplicate language code: " + newCode);
			}
		} catch(Exception e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(br);
			Closer.close(isr);
			Closer.close(in);
		}

		return codes;
	}
	
	public static void main(String[] args) {
		for(LanguageCode c : loadFromTabFile()) {
			System.out.println(c);
		}
	}
	
}
