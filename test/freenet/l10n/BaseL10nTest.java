package freenet.l10n;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Iterator;

import org.junit.Test;

import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.TestProperty;

public class BaseL10nTest {
    
    @Test
    public void testAddL10nSubstitution() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode boldNode = new HTMLNode("b");
        l10n.addL10nSubstitution(node, "test.substitution",
                new String[] {"bold"},
                new HTMLNode[] {boldNode});
        assertEquals("Text with <b>loud</b> string", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionExtra() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode boldNode = new HTMLNode("b");
        HTMLNode extraNode = new HTMLNode("extra");
        l10n.addL10nSubstitution(node, "test.substitution",
                new String[] {"bold", "extra"},
                new HTMLNode[] {boldNode, extraNode});
        assertEquals("Text with <b>loud</b> string", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionUnclosed() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode imgNode = new HTMLNode("img");
        l10n.addL10nSubstitution(node, "test.unclosedSubstitution",
                new String[] {"image"},
                new HTMLNode[] {imgNode});
        assertEquals("Text with <img /> unclosed substitution", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionUnclosedMissing() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        l10n.addL10nSubstitution(node, "test.unclosedSubstitution",
                new String[] {},
                new HTMLNode[] {});
        assertEquals("Text with  unclosed substitution", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionMultiple() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode rep1Node = new HTMLNode("r1");
        HTMLNode rep2Node = new HTMLNode("r2");
        HTMLNode rep3Node = new HTMLNode("r3");
        l10n.addL10nSubstitution(node, "test.multipleSubstitution",
                new String[] {"rep2", "rep1", "rep3"},
                new HTMLNode[] {rep2Node, rep1Node, rep3Node});
        assertEquals("<r1>Rep 1</r1><r2>Rep 2</r2> and <r3>Rep 3</r3>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionMissing() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode rep2Node = new HTMLNode("r2");
        HTMLNode rep3Node = new HTMLNode("r3");
        l10n.addL10nSubstitution(node, "test.multipleSubstitution",
                new String[] {"rep2", "rep3"},
                new HTMLNode[] {rep2Node, rep3Node});
        assertEquals("Rep 1<r2>Rep 2</r2> and <r3>Rep 3</r3>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionNested() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode innerNode = new HTMLNode("in");
        HTMLNode outerNode = new HTMLNode("out");
        l10n.addL10nSubstitution(node, "test.nestedSubstitution",
                new String[] {"inner", "outer"},
                new HTMLNode[] {innerNode, outerNode});
        assertEquals("<out>Text and <in>replacement</in></out>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionDouble() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode tagNode = new HTMLNode("tag");
        l10n.addL10nSubstitution(node, "test.doubleSubstitution",
                new String[] {"tag"},
                new HTMLNode[] {tagNode});
        assertEquals("<tag></tag>content<tag></tag>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionSelfNested() throws Exception {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode tagNode = new HTMLNode("tag");
        l10n.addL10nSubstitution(node, "test.selfNestedSubstitution",
                new String[] {"tag"},
                new HTMLNode[] {tagNode});
        // it would be nice to handle this correctly, but it seems like more trouble than it's worth
        //assertEquals("<tag>content <tag>nested</tag></tag>", node.generateChildren());
        assertEquals("test.selfNestedSubstitution", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionSelfNestedEmpty() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode tagNode = new HTMLNode("tag");
        l10n.addL10nSubstitution(node, "test.emptySelfNestedSubstitution",
                new String[] {"tag"},
                new HTMLNode[] {tagNode});
        assertEquals("<tag>content <tag></tag>nested</tag>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionMissingBrace() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode okNode = new HTMLNode("ok");
        l10n.addL10nSubstitution(node, "test.missingBraceSubstitution",
                new String[] {"ok"},
                new HTMLNode[] {okNode});
        assertEquals("test.missingBraceSubstitution", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionUnmatchedClose() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        HTMLNode node = new HTMLNode("div");
        HTMLNode okNode = new HTMLNode("ok");
        l10n.addL10nSubstitution(node, "test.unmatchedCloseSubstitution",
                new String[] {"ok"},
                new HTMLNode[] {okNode});
        assertEquals("test.unmatchedCloseSubstitution", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionFallback() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        HTMLNode node = new HTMLNode("div");
        HTMLNode tagNode = new HTMLNode("tag");
        l10n.addL10nSubstitution(node, "test.badSubstitutionFallback",
                new String[] {"tag"},
                new HTMLNode[] {tagNode});
        assertEquals("Fallback <tag></tag>", node.generateChildren());
    }
    
    @Test
    public void testAddL10nSubstitutionMissingFallback() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        HTMLNode node = new HTMLNode("div");
        HTMLNode boldNode = new HTMLNode("b");
        l10n.addL10nSubstitution(node, "test.substitution",
                new String[] {"bold"},
                new HTMLNode[] {boldNode});
        assertEquals("Text with <b>loud</b> string", node.generateChildren());
    }
    
    @Test
    public void testGetString() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        String value = l10n.getString("test.sanity");
        assertEquals("Sane", value);
    }

    @Test
    public void testGetStringOverridden() {
        BaseL10n l10n = createTestL10n(LANGUAGE.ENGLISH);
        String value = l10n.getString("test.override");
        assertEquals("Overridden", value);
    }

    @Test
    public void testGetStringFallback() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getString("test.sanity");
        assertEquals("Sane", value);
    }

    @Test
    public void testGetStringFallbackOverridden() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getString("test.override");
        assertEquals("Not overridden", value);
    }

    @Test
    public void testGetStringNonexistent() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getString("test.nonexistent");
        assertEquals("test.nonexistent", value);
    }

    @Test
    public void testGetDefaultString() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getDefaultString("test.badSubstitutionFallback");
        assertEquals("Fallback ${tag}", value);
    }

    @Test
    public void testGetDefaultStringFallback() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getDefaultString("test.sanity");
        assertEquals("Sane", value);
    }

    @Test
    public void testGetDefaultStringNonexistent() {
        BaseL10n l10n = createTestL10n(LANGUAGE.GERMAN);
        String value = l10n.getDefaultString("test.nonexistent");
        assertEquals("test.nonexistent", value);
    }
    
    @Test
    public void testStrings() throws Exception {
        for (LANGUAGE lang : LANGUAGE.values()) {
            BaseL10n l10n = createL10n(lang);
            SimpleFieldSet fields = l10n.getCurrentLanguageTranslation();
            if (fields != null) {
                for (Iterator<String> itr = fields.keyIterator(); itr.hasNext();) {
                    String key = itr.next();
                    String value = fields.get(key);
                    try {
                        l10n.attemptParse(value);
                    } catch (L10nParseException e) {
                        fail("Error in "+key+" for "+lang+": "+e.getMessage());
                    }
                }
            }
        }
    }

    public static final BaseL10n createL10n(LANGUAGE lang) {
        File overrideFile = new File(TestProperty.L10nPath_main, "freenet.l10n.${lang}.override.properties");
        return new BaseL10n("freenet/l10n/", "freenet.l10n.${lang}.properties",
                overrideFile.getPath(), lang);
    }

    public static final BaseL10n createTestL10n(LANGUAGE lang) {
        File overrideFile = new File(TestProperty.L10nPath_test, "freenet.l10n.${lang}.override.properties");
        return new BaseL10n("freenet/l10n/", "freenet.l10n.${lang}.test.properties",
                overrideFile.getPath(), lang);
    }

    /**
     * Installs a {@link #createTestL10n(LANGUAGE) BaseL10n} with
     * translations read from the test classpath into the global
     * {@link NodeL10n}, allowing tests for translation keys.
     */
    public static void useTestTranslation() {
        NodeL10n.setBase(createTestL10n(LANGUAGE.ENGLISH));
    }

}
