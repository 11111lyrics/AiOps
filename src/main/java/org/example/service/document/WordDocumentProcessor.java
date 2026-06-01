package org.example.service.document;

import org.example.dto.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Word 提取：Apache Tika（支持 .doc / .docx）
 */
@Component
public class WordDocumentProcessor extends AbstractDocumentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WordDocumentProcessor.class);
    private static final Set<String> EXTENSIONS = Set.of("doc", "docx");

    @Autowired
    private PlainTextChunkStrategy plainTextChunkStrategy;

    @Override
    public boolean supports(String extension) {
        return EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    protected ChunkStrategy getChunkStrategy() {
        return plainTextChunkStrategy;
    }

    @Override
    public List<TextBlock> extract(Path file) throws Exception {
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file.toFile()));
        List<Document> documents = reader.read();

        List<TextBlock> blocks = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text != null && !text.trim().isEmpty()) {
                blocks.add(new TextBlock(text.trim()));
            }
        }

        if (blocks.isEmpty()) {
            throw new IllegalStateException("Word 文档提取结果为空: " + file);
        }

        logger.info("Word 提取完成: {}, 文本长度: {} 字符", file, blocks.get(0).getContent().length());
        return blocks;
    }
}
