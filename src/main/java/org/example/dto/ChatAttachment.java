package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 会话级聊天附件元数据。文件只存在于当前会话目录，不进入知识库。
 */
@Getter
@Setter
public class ChatAttachment {

    private String id;
    private String sessionId;
    private String fileName;
    private String storedFileName;
    private long size;
}
