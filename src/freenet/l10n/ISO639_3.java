/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;

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
 */
public final class ISO639_3 {

	/**
	 * A class which represents a language code. It was translated from the example SQL-table-definition on
	 * http://www.sil.org/iso639-3/download.asp
	 * 
	 * The quoted texts on the JavaDoc of the member variables are the original comments from the SQL-table-definition.
	 * 
	 * All members are final, therefore objects of this class do not need to be cloned by clients of ISO639_3.
	 */
	public static final class LanguageCode implements Comparable<LanguageCode> {
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

		public static enum Scope {
			Individual,
			Macrolanguage,
			Special;
			
			private static Scope fromTabFile(String abbreviation) {
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

		public static enum Type {
			Ancient,
			Constructed,
			Extinct,
			Historical,
			Living,
			Special;
			
			private static Type fromTabFile(String abbreviation) {
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
		
		@Override
		public boolean equals(Object o) {
			if(!(o instanceof LanguageCode))
				return false;
			
			return equals((LanguageCode)o);
		}
		
		@Override
		public int hashCode() {
			return id.hashCode();
		}

		@Override
		public int compareTo(LanguageCode o) {
			return id.compareTo(o.id);
		}
		
		@Override
		public String toString() {
			return new String(id) + " = " + referenceName + " (scope: " + scope + "; type: " + type + ")";
		}

	}
	
	private static Hashtable<String, LanguageCode> loadFromTabFile() {
		final Hashtable<String, LanguageCode> codes = new Hashtable<String, LanguageCode>(7705 * 2);

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
				
				if(codes.put(newCode.id, newCode) != null)
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
	

	private final Map<String, LanguageCode> allLanguagesCache;
	
	/**
	 * Constructs a new ISO639_3 and loads the list of languages from the .tab file in the classpath.
	 * The list is cached for the lifetime of this ISO639_3 object, make sure not to keep the ISO639_3 object alive
	 * if you only use a small part of all languages.
	 *  
	 * @throws RuntimeException If the .tab file is not present in the classpath or if parsing fails.
	 */
	public ISO639_3() {
		allLanguagesCache = Collections.unmodifiableMap(loadFromTabFile());
	}
	
	/**
	 * Gets a list of all languages.
	 * 
	 * @return Returns the map of all ISO639-3 language codes. The key in the returned list is the ID of the language code,
	 * which is the 3-letter code of ISO639-3. The given map is unmodifiable since it is used for the cache.
	 */
	public final Map<String, LanguageCode> getLanguages() {		
		return allLanguagesCache;
	}
	
	/**
	 * Gets a filtered list of languages. The list is cached for the lifetime of this ISO639_3 object.
	 * 
	 * @param scope Must not be null.
	 * @param type Must not be null.
	 * @return Gets a {@link Hashtable} of language codes with the given scope and type. The key in the returned list is the ID 
	 * 			of the language code, which is the 3-letter code of ISO639-3. The given Hashtable is free for modification.
	 */
	public final Hashtable<String, LanguageCode> getLanguagesByScopeAndType(LanguageCode.Scope scope, LanguageCode.Type type) {
		final Map<String, LanguageCode> all = getLanguages();
		final Hashtable<String, LanguageCode> result = new Hashtable<String, LanguageCode>();
		
		for(final LanguageCode c : all.values()) {
			if(c.scope.equals(scope) && c.type.equals(type))
				result.put(c.id, c); // We do not clone the code because all its fields are final.
		}
		
		return result;
	}
	
	/**
	 * @return The special symbolic language code which is supposed to be a category for multiple languages.
	 */
	public final LanguageCode getMultilingualCode() {
		return getLanguages().get("mul");
		
	}
	
	public static void main(String[] args) {
		for(LanguageCode c : loadFromTabFile().values()) {
			System.out.println(c);
		}
	}
	
}
