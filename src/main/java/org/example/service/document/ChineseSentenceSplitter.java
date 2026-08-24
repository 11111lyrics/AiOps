package org.example.service.document;

import com.hankcs.hanlp.utility.SentencesUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文句边界识别，委托 HanLP {@link SentencesUtil}。
 * {@code shortest=false}：只在句末切分（。！？；等），逗号不切开。
 */
@Component
public class ChineseSentenceSplitter {

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> raw = SentencesUtil.toSentenceList(text, false);
        List<String> sentences = new ArrayList<>();
        if (raw != null) {
            for (String sentence : raw) {
                if (sentence == null) {
                    continue;
                }
                String trimmed = sentence.trim();
                if (!trimmed.isEmpty()) {
                    sentences.add(trimmed);
                }
            }
        }
        if (sentences.isEmpty()) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }
}
