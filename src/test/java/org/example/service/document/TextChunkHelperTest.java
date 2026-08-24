package org.example.service.document;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextChunkHelperTest {

    @Test
    void fillsBySentenceAndDoesNotSplitOnComma() {
        TextChunkHelper helper = helper(25, 0);
        String text = "数据库连接失败，请检查账号密码。随后重启课程服务并观察日志。";

        List<DocumentChunk> chunks = helper.chunkText(text, "t", 0, 0, Map.of());

        assertEquals(2, chunks.size());
        assertEquals("数据库连接失败，请检查账号密码。", chunks.get(0).getContent());
        assertEquals("随后重启课程服务并观察日志。", chunks.get(1).getContent());
    }

    @Test
    void prefersParagraphBoundaryWhenNextParagraphWouldOverflow() {
        TextChunkHelper helper = helper(18, 0);
        String text = "第一段只有一句结束。\n\n第二段也是一句完整的话。";

        List<DocumentChunk> chunks = helper.chunkText(text, "t", 0, 0, Map.of());

        assertEquals(2, chunks.size());
        assertEquals("第一段只有一句结束。", chunks.get(0).getContent());
        assertEquals("第二段也是一句完整的话。", chunks.get(1).getContent());
    }

    private static TextChunkHelper helper(int maxSize, int overlap) {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(maxSize);
        config.setOverlap(overlap);
        return new TextChunkHelper(config, new ChineseSentenceSplitter());
    }
}
