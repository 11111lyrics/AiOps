package org.example.service.document;

import org.example.dto.TextBlock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Component
public class TextDocumentProcessor extends AbstractDocumentProcessor {

    private static final Set<String> EXTENSIONS = Set.of("txt");

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
        return List.of(readPlainText(file));
    }
}
