package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 提取层输出：一段待分片的文本及其上下文元数据
 */
@Getter
@Setter
public class TextBlock {

    private String content;
    private String title;
    private int startIndex;
    private Map<String, Object> metadata = new HashMap<>();

    public TextBlock() {
    }

    public TextBlock(String content) {
        this.content = content;
    }

    public TextBlock(String content, String title) {
        this.content = content;
        this.title = title;
    }

    public TextBlock putMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }
}
