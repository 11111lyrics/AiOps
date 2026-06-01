package org.example.service.document;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 共享分片工具：段落切分、字符级切分与重叠处理
 */
@Component
public class TextChunkHelper {

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /**
     * 对单个文本块按段落 → 字符规则分片
     */
    public List<DocumentChunk> chunkText(String content, String title, int baseStartIndex,
                                         int startChunkIndex, Map<String, Object> extraMetadata) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        if (content.length() <= chunkConfig.getMaxSize()) {
            chunks.add(buildChunk(content, title, baseStartIndex, baseStartIndex + content.length(),
                    startChunkIndex, extraMetadata));
            return chunks;
        }

        List<String> paragraphs = splitByParagraphs(content);
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = baseStartIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            if (currentChunk.length() > 0
                    && currentChunk.length() + paragraph.length() > chunkConfig.getMaxSize()) {
                String chunkContent = currentChunk.toString().trim();
                chunks.add(buildChunk(chunkContent, title, currentStartIndex,
                        currentStartIndex + chunkContent.length(), chunkIndex++, extraMetadata));

                String overlap = getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }
            currentChunk.append(paragraph).append("\n\n");
        }

        if (currentChunk.length() > 0) {
            String chunkContent = currentChunk.toString().trim();
            chunks.add(buildChunk(chunkContent, title, currentStartIndex,
                    currentStartIndex + chunkContent.length(), chunkIndex, extraMetadata));
        }

        return chunks;
    }

    public List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    public String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }

        String overlap = text.substring(text.length() - overlapSize);
        int lastSentenceEnd = Math.max(
                overlap.lastIndexOf('。'),
                Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
        );

        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }
        return overlap.trim();
    }

    private DocumentChunk buildChunk(String content, String title, int startIndex, int endIndex,
                                     int chunkIndex, Map<String, Object> extraMetadata) {
        DocumentChunk chunk = new DocumentChunk(content, startIndex, endIndex, chunkIndex);
        chunk.setTitle(title);
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            chunk.setExtraMetadata(extraMetadata);
        }
        return chunk;
    }
}
