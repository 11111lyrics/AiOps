package org.example.service.document;

import org.example.dto.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF 提取：优先 PagePdfDocumentReader 按页提取，失败时回退 TikaDocumentReader
 */
@Component
public class PdfDocumentProcessor extends AbstractDocumentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PdfDocumentProcessor.class);
    private static final Set<String> EXTENSIONS = Set.of("pdf");

    @Autowired
    private PdfChunkStrategy pdfChunkStrategy;

    @Override
    public boolean supports(String extension) {
        return EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    protected ChunkStrategy getChunkStrategy() {
        return pdfChunkStrategy;
    }

    @Override
    public List<TextBlock> extract(Path file) throws Exception {
        try {
            return extractByPage(file);
        } catch (Exception e) {
            logger.warn("PDF 按页提取失败，回退 Tika 整文提取: {}, 原因: {}", file, e.getMessage());
            return extractByTikaSafely(file);
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            logger.warn("PDFBox 字体初始化失败，回退 Tika 整文提取: {}, 原因: {}", file, e.getMessage());
            return extractByTikaSafely(file);
        }
    }

    private List<TextBlock> extractByTikaSafely(Path file) throws Exception {
        try {
            return extractByTika(file);
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            throw new IllegalStateException("PDF Tika 提取失败: " + e.getMessage(), e);
        }
    }

    private List<TextBlock> extractByPage(Path file) {
        FileSystemResource resource = new FileSystemResource(file.toFile());
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> documents = reader.read();

        List<TextBlock> blocks = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String text = doc.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }

            int pageNumber = i + 1;
            Object metaPage = doc.getMetadata().get("page_number");
            if (metaPage instanceof Number) {
                pageNumber = ((Number) metaPage).intValue();
            }

            TextBlock block = new TextBlock(text.trim(), "第 " + pageNumber + " 页");
            block.setStartIndex(offset);
            block.putMetadata("pageNumber", pageNumber);
            blocks.add(block);
            offset += text.length();
        }

        if (blocks.isEmpty()) {
            throw new IllegalStateException("PDF 按页提取结果为空");
        }
        return blocks;
    }

    private List<TextBlock> extractByTika(Path file) {
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
            throw new IllegalStateException("PDF Tika 提取结果为空");
        }
        return blocks;
    }
}
