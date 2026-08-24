package org.example.service.document;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 共享分片工具：段落优先、按句填充；单句超过 maxSize 时再按字符硬切。
 */
@Component
public class TextChunkHelper {

    private static final String PARAGRAPH_BREAK = "\n\n";

    private final DocumentChunkConfig chunkConfig;
    private final ChineseSentenceSplitter sentenceSplitter;

    public TextChunkHelper(DocumentChunkConfig chunkConfig, ChineseSentenceSplitter sentenceSplitter) {
        this.chunkConfig = chunkConfig;
        this.sentenceSplitter = sentenceSplitter;
    }

    /**
     * 对单个文本块分片：句子一条条填入当前 chunk，加满则封存并开新 chunk。
     * 下一段整段落不下时，优先在段落边界切开。
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

        ChunkBuilder builder = new ChunkBuilder(title, baseStartIndex, startChunkIndex, extraMetadata, chunks);
        for (String paragraph : splitByParagraphs(content)) {
            List<String> sentences = sentenceSplitter.split(paragraph);
            if (sentences.isEmpty()) {
                continue;
            }

            int paragraphLen = joinedLength(sentences);
            if (builder.hasContent()
                    && builder.length() + PARAGRAPH_BREAK.length() + paragraphLen > chunkConfig.getMaxSize()) {
                builder.flush();
            }

            boolean startOfParagraph = true;
            for (String sentence : sentences) {
                builder.appendSentence(sentence, startOfParagraph);
                startOfParagraph = false;
            }
        }
        builder.flushRemainder();
        return chunks;
    }

    public List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = content.split("\\R{2,}");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        if (paragraphs.isEmpty()) {
            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    public String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text == null ? 0 : text.length());
        if (overlapSize <= 0 || text == null || text.isEmpty()) {
            return "";
        }

        List<String> sentences = sentenceSplitter.split(text);
        if (sentences.isEmpty()) {
            return text.substring(text.length() - overlapSize).trim();
        }

        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            if (overlap.length() > 0 && overlap.length() + sentence.length() > overlapSize) {
                break;
            }
            if (overlap.length() == 0 && sentence.length() > overlapSize) {
                return sentence.substring(sentence.length() - overlapSize).trim();
            }
            overlap.insert(0, sentence);
        }
        return overlap.toString();
    }

    private static int joinedLength(List<String> sentences) {
        int length = 0;
        for (String sentence : sentences) {
            length += sentence.length();
        }
        return length;
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

    private final class ChunkBuilder {
        private final String title;
        private final Map<String, Object> extraMetadata;
        private final List<DocumentChunk> chunks;
        private final StringBuilder buffer = new StringBuilder();
        private int currentStartIndex;
        private int chunkIndex;

        private ChunkBuilder(String title, int baseStartIndex, int startChunkIndex,
                             Map<String, Object> extraMetadata, List<DocumentChunk> chunks) {
            this.title = title;
            this.extraMetadata = extraMetadata;
            this.chunks = chunks;
            this.currentStartIndex = baseStartIndex;
            this.chunkIndex = startChunkIndex;
        }

        private boolean hasContent() {
            return buffer.length() > 0;
        }

        private int length() {
            return buffer.length();
        }

        private void appendSentence(String sentence, boolean startOfParagraph) {
            if (sentence.length() > chunkConfig.getMaxSize()) {
                if (hasContent()) {
                    flush();
                }
                appendOversizedSentence(sentence);
                return;
            }

            int separatorLen = (hasContent() && startOfParagraph) ? PARAGRAPH_BREAK.length() : 0;
            if (hasContent() && length() + separatorLen + sentence.length() > chunkConfig.getMaxSize()) {
                flush();
                separatorLen = 0;
                if (hasContent() && length() + sentence.length() > chunkConfig.getMaxSize()) {
                    buffer.setLength(0);
                }
            }

            if (hasContent() && startOfParagraph && separatorLen > 0) {
                buffer.append(PARAGRAPH_BREAK);
            }
            buffer.append(sentence);
        }

        private void appendOversizedSentence(String sentence) {
            int maxSize = chunkConfig.getMaxSize();
            int overlap = Math.max(0, chunkConfig.getOverlap());
            int start = 0;
            while (start < sentence.length()) {
                int end = Math.min(start + maxSize, sentence.length());
                buffer.append(sentence, start, end);
                if (end >= sentence.length()) {
                    break;
                }
                flush();
                int next = end - overlap;
                start = next <= start ? end : next;
            }
        }

        private void flush() {
            emit(true);
        }

        private void flushRemainder() {
            emit(false);
        }

        private void emit(boolean seedOverlap) {
            String chunkContent = buffer.toString().trim();
            if (chunkContent.isEmpty()) {
                buffer.setLength(0);
                return;
            }
            chunks.add(buildChunk(chunkContent, title, currentStartIndex,
                    currentStartIndex + chunkContent.length(), chunkIndex++, extraMetadata));
            String overlap = seedOverlap ? getOverlapText(chunkContent) : "";
            buffer.setLength(0);
            if (!overlap.isEmpty()) {
                buffer.append(overlap);
            }
            currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
        }
    }
}
