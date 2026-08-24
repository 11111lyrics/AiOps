package org.example.service.document;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.example.dto.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分片策略：按 # 标题 → 段落优先 → 按句填充
 */
@Component
public class MarkdownChunkStrategy implements ChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownChunkStrategy.class);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    @Autowired
    private DocumentChunkConfig chunkConfig;

    @Autowired
    private TextChunkHelper textChunkHelper;

    @Override
    public List<DocumentChunk> chunk(List<TextBlock> blocks, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return chunks;
        }

        String fullContent = blocks.get(0).getContent();
        if (fullContent == null || fullContent.trim().isEmpty()) {
            logger.warn("Markdown 内容为空: {}", filePath);
            return chunks;
        }

        List<Section> sections = splitByHeadings(fullContent);
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex, blocks.get(0).getMetadata());
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("Markdown 分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;

        while (matcher.find()) {
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentTitle, sectionContent, lastEnd));
                }
            }
            currentTitle = matcher.group(2).trim();
            lastEnd = matcher.start();
        }

        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentTitle, sectionContent, lastEnd));
            }
        }

        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0));
        }
        return sections;
    }

    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex,
                                               java.util.Map<String, Object> extraMetadata) {
        if (section.content.length() <= chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(
                    section.content, section.startIndex, section.startIndex + section.content.length(), startChunkIndex);
            chunk.setTitle(section.title);
            if (extraMetadata != null && !extraMetadata.isEmpty()) {
                chunk.setExtraMetadata(extraMetadata);
            }
            return List.of(chunk);
        }

        return textChunkHelper.chunkText(section.content, section.title, section.startIndex,
                startChunkIndex, extraMetadata);
    }

    private static class Section {
        final String title;
        final String content;
        final int startIndex;

        Section(String title, String content, int startIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
        }
    }
}
