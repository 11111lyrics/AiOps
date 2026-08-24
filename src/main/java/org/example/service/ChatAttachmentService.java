package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.FileUploadConfig;
import org.example.dto.ChatAttachment;
import org.example.dto.TextBlock;
import org.example.service.document.DocumentProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聊天附件：按会话落盘，抽取文本供当轮对话使用，不调用向量索引。
 */
@Service
public class ChatAttachmentService {

    private static final Logger logger = LoggerFactory.getLogger(ChatAttachmentService.class);
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");
    private static final Pattern ATTACHMENT_ID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String DEFAULT_QUESTION = "请阅读下面的附件并给出要点。";

    private final FileUploadConfig fileUploadConfig;
    private final DocumentProcessorRegistry processorRegistry;
    private final ObjectMapper objectMapper;

    @Value("${chat.attachment.max-chars:24000}")
    private int maxChars;

    @Value("${chat.attachment.max-files:5}")
    private int maxFiles;

    public ChatAttachmentService(FileUploadConfig fileUploadConfig,
                                 DocumentProcessorRegistry processorRegistry,
                                 ObjectMapper objectMapper) {
        this.fileUploadConfig = fileUploadConfig;
        this.processorRegistry = processorRegistry;
        this.objectMapper = objectMapper;
    }

    public ChatAttachment save(String sessionId, MultipartFile file) throws IOException {
        String safeSessionId = requireSessionId(sessionId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String extension = extensionOf(originalFilename);
        if (!processorRegistry.isSupportedExtension(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        Path dir = sessionDir(safeSessionId);
        Files.createDirectories(dir);

        String attachmentId = UUID.randomUUID().toString();
        String storedFileName = attachmentId + "." + extension;
        Path storedPath = dir.resolve(storedFileName).normalize();
        assertUnder(dir, storedPath);
        try (var input = file.getInputStream()) {
            Files.copy(input, storedPath);
        }

        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(attachmentId);
        attachment.setSessionId(safeSessionId);
        attachment.setFileName(Path.of(originalFilename).getFileName().toString());
        attachment.setStoredFileName(storedFileName);
        attachment.setSize(file.getSize());
        writeMeta(dir, attachment);

        logger.info("聊天附件已保存（不入知识库） session={}, id={}, file={}",
                safeSessionId, attachmentId, attachment.getFileName());
        return attachment;
    }

    public void delete(String sessionId, String attachmentId) throws IOException {
        ChatAttachment attachment = load(sessionId, attachmentId);
        Path dir = sessionDir(attachment.getSessionId());
        Files.deleteIfExists(dir.resolve(attachment.getStoredFileName()));
        Files.deleteIfExists(metaPath(dir, attachment.getId()));
        logger.info("已删除聊天附件 session={}, id={}", attachment.getSessionId(), attachment.getId());
    }

    public void deleteSession(String sessionId) {
        if (!StringUtils.hasText(sessionId) || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return;
        }
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            logger.warn("删除会话附件失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            logger.warn("清理会话附件目录失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 把本轮附件文本拼进用户消息。无附件时原样返回问题。
     */
    public String buildUserContent(String sessionId, String question, List<String> attachmentIds) {
        String trimmed = question == null ? "" : question.trim();
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return trimmed;
        }
        if (attachmentIds.size() > maxFiles) {
            throw new IllegalArgumentException("单轮最多附加 " + maxFiles + " 个文件");
        }

        StringBuilder builder = new StringBuilder();
        builder.append(trimmed.isEmpty() ? DEFAULT_QUESTION : trimmed);
        builder.append("\n\n以下是本轮对话附件，仅用于当前会话，未写入内部知识库。");
        builder.append("请直接根据附件内容作答，不要用 queryInternalDocs 查找这些刚上传的文件。\n");

        int remaining = Math.max(1000, maxChars);
        for (String attachmentId : attachmentIds) {
            ChatAttachment attachment = load(sessionId, attachmentId);
            String extracted = extractText(attachment);
            if (extracted.length() > remaining) {
                extracted = extracted.substring(0, remaining)
                        + "\n…（附件过长，已截断。当前为文本提取，不含图片理解。）";
                remaining = 0;
            } else {
                remaining -= extracted.length();
            }
            builder.append("\n----- 附件: ").append(attachment.getFileName()).append(" -----\n");
            builder.append(extracted).append('\n');
            builder.append("----- 附件结束 -----\n");
            if (remaining <= 0) {
                break;
            }
        }
        return builder.toString();
    }

    public boolean hasAttachments(List<String> attachmentIds) {
        return attachmentIds != null && !attachmentIds.isEmpty();
    }

    private String extractText(ChatAttachment attachment) {
        Path dir = sessionDir(attachment.getSessionId());
        Path file = dir.resolve(attachment.getStoredFileName()).normalize();
        assertUnder(dir, file);
        if (!Files.isRegularFile(file)) {
            return "（附件文件缺失。）";
        }
        try {
            List<TextBlock> blocks = processorRegistry.resolve(file.toString()).extract(file);
            List<String> parts = new ArrayList<>();
            if (blocks != null) {
                for (TextBlock block : blocks) {
                    if (block != null && StringUtils.hasText(block.getContent())) {
                        if (StringUtils.hasText(block.getTitle())) {
                            parts.add("[" + block.getTitle() + "]\n" + block.getContent().trim());
                        } else {
                            parts.add(block.getContent().trim());
                        }
                    }
                }
            }
            if (parts.isEmpty()) {
                return "（未能提取到文本。可能是扫描件或纯图片；当前对话模型还不能理解图片。）";
            }
            return String.join("\n\n", parts);
        } catch (Exception e) {
            logger.warn("聊天附件文本提取失败 id={}, file={}: {}",
                    attachment.getId(), attachment.getFileName(), e.getMessage());
            return "（文本提取失败: " + e.getMessage() + "）";
        }
    }

    private ChatAttachment load(String sessionId, String attachmentId) {
        String safeSessionId = requireSessionId(sessionId);
        if (!StringUtils.hasText(attachmentId) || !ATTACHMENT_ID_PATTERN.matcher(attachmentId).matches()) {
            throw new IllegalArgumentException("无效的附件 ID");
        }
        Path meta = metaPath(sessionDir(safeSessionId), attachmentId);
        if (!Files.isRegularFile(meta)) {
            throw new IllegalArgumentException("附件不存在或不属于当前会话: " + attachmentId);
        }
        try {
            ChatAttachment attachment = objectMapper.readValue(meta.toFile(), ChatAttachment.class);
            if (!safeSessionId.equals(attachment.getSessionId())) {
                throw new IllegalArgumentException("附件不属于当前会话");
            }
            return attachment;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取附件元数据失败: " + attachmentId);
        }
    }

    private void writeMeta(Path dir, ChatAttachment attachment) throws IOException {
        objectMapper.writeValue(metaPath(dir, attachment.getId()).toFile(), attachment);
    }

    private Path metaPath(Path dir, String attachmentId) {
        return dir.resolve(attachmentId + ".json");
    }

    private Path sessionDir(String sessionId) {
        Path root = Paths.get(fileUploadConfig.getPath(), "chat-attachments")
                .toAbsolutePath()
                .normalize();
        Path dir = root.resolve(sessionId).normalize();
        assertUnder(root, dir);
        return dir;
    }

    private String requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId) || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("无效的会话 ID");
        }
        return sessionId;
    }

    private void assertUnder(Path parent, Path child) {
        if (!child.startsWith(parent)) {
            throw new IllegalArgumentException("非法文件路径");
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
