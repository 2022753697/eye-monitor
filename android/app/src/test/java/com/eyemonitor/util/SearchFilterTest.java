package com.eyemonitor.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 5 消息搜索筛选参数纯逻辑单测（无 Android 依赖，纯 JVM）。
 */
public class SearchFilterTest {

    @Test
    public void kindsFor_all_includesAllKinds() {
        List<String> kinds = SearchFilter.kindsFor(SearchFilter.TYPE_ALL);
        assertTrue(kinds.contains("chat"));
        assertTrue(kinds.contains("media"));
        assertTrue(kinds.contains("voice"));
        assertTrue(kinds.contains("system"));
        assertTrue(kinds.contains("task"));
    }

    @Test
    public void kindsFor_text_returnsChat() {
        assertEquals(List.of("chat"), SearchFilter.kindsFor(SearchFilter.TYPE_TEXT));
    }

    @Test
    public void kindsFor_voice_includesVoiceAndMedia() {
        List<String> kinds = SearchFilter.kindsFor(SearchFilter.TYPE_VOICE);
        assertTrue(kinds.contains("voice"));
        assertTrue(kinds.contains("media"));
    }

    @Test
    public void startTsFor_allTime_returnsZero() {
        assertEquals(0L, SearchFilter.startTsFor(0, 1_000_000L));
    }

    @Test
    public void startTsFor_7days_coversWeek() {
        long now = 1_000_000_000L;
        long start = SearchFilter.startTsFor(7, now);
        assertEquals(now - 7L * SearchFilter.DAY_MS, start);
        assertTrue(start < now);
    }

    @Test
    public void displayText_media_placeholder() {
        assertEquals("[图片/视频]", SearchFilter.displayText("media", "some-file-id"));
    }

    @Test
    public void displayText_chat_originalText() {
        assertEquals("今晚去看电影吗", SearchFilter.displayText("chat", "今晚去看电影吗"));
    }
}