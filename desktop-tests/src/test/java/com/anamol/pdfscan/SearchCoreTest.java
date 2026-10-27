package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Transcript round trip and full-text matching. */
public class SearchCoreTest {
    private static final String TITLE = "Lab notes + invoice";
    private static final List<String> PAGES = SearchCore.pagesOf(SearchCore.build(TITLE, Arrays.asList(
            "Invoice number 4521\nTotal due: Rs 12,400\nPayable within 30 days.",
            null,
            "Gel electrophoresis setup.\nAgarose 1% in TAE buffer, run at 100 V for 45 minutes. "
                    + "Stain with EtBr and image under UV.",
            "")));

    private static String highlighted(SearchCore.Hit h) {
        return h.snippet.substring(h.matchStart, h.matchStart + h.matchLength);
    }

    @Test
    public void transcriptRoundTrip() {
        assertEquals(4, PAGES.size());
        assertTrue(PAGES.get(0).startsWith("Invoice number 4521"));
        assertEquals("page not recognised yet parses as empty", "", PAGES.get(1));
        assertEquals("", PAGES.get(3));
    }

    @Test
    public void findsWordAndHighlightsIt() {
        SearchCore.Hit h = SearchCore.match(TITLE, PAGES, "agarose");
        assertNotNull(h);
        assertEquals(Collections.singletonList(3), h.pages);
        assertEquals("Agarose", highlighted(h));
    }

    @Test
    public void wordsInAnyOrderOnSamePage() {
        SearchCore.Hit h = SearchCore.match(TITLE, PAGES, "UV  stain");
        assertNotNull(h);
        assertEquals(Collections.singletonList(3), h.pages);
    }

    @Test
    public void wordsSpreadAcrossPagesStillMatchDocument() {
        SearchCore.Hit h = SearchCore.match(TITLE, PAGES, "invoice agarose");
        assertNotNull(h);
        assertFalse(h.titleMatch);
        assertEquals(Collections.singletonList(1), h.pages);
    }

    @Test
    public void titleOnlyMatch() {
        SearchCore.Hit h = SearchCore.match(TITLE, PAGES, "LAB NOTES");
        assertNotNull(h);
        assertTrue(h.titleMatch);
        assertTrue(h.pages.isEmpty());
    }

    @Test
    public void noMatchAndBlankQuery() {
        assertNull(SearchCore.match(TITLE, PAGES, "photosynthesis"));
        assertNull(SearchCore.match(TITLE, PAGES, "   "));
    }

    @Test
    public void longPageGivesShortSnippetWithEllipses() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) text.append("word").append(i).append(' ');
        text.append("needle ");
        for (int i = 0; i < 50; i++) text.append("tail").append(i).append(' ');
        SearchCore.Hit h = SearchCore.match("x", Collections.singletonList(text.toString()), "needle");
        assertNotNull(h);
        assertTrue(h.snippet.startsWith("…"));
        assertTrue(h.snippet.endsWith("…"));
        assertTrue(h.snippet.length() < 130);
        assertEquals("needle", highlighted(h));
    }

    @Test
    public void unknownFormatHasNoPages() {
        assertTrue(SearchCore.pagesOf("garbage without markers").isEmpty());
    }

    @Test
    public void chineseAndJapaneseLoseOcrSpacesButOthersKeepThem() {
        org.junit.Assert.assertEquals("中文文档 and English", SearchCore.compactCjk("中 文 文 档 and English"));
        org.junit.Assert.assertEquals("日本語のテキスト", SearchCore.compactCjk("日本 語 の テキスト"));
        org.junit.Assert.assertEquals("한국어 문서", SearchCore.compactCjk("한국어 문서"));
        org.junit.Assert.assertEquals("हिंदी पाठ", SearchCore.compactCjk("हिंदी पाठ"));
        org.junit.Assert.assertNotNull(SearchCore.match("Notes", java.util.Arrays.asList(
                SearchCore.compactCjk("这是 一 个 中文 文档")), "中文文档"));
    }
}
