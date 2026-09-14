package org.example.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClsPropertiesTest {

    @Test
    void resolveTopicNameKeepsMysqlSlowWithoutServiceSuffix() {
        ClsProperties props = new ClsProperties();
        props.setRegion("ap-chengdu");
        assertEquals("tjxt-dev-user-service-log-ap-chengdu", props.resolveTopicName("user"));
        assertEquals("tjxt-dev-user-service-log-ap-chengdu", props.resolveTopicName("user-service"));
        assertEquals("tjxt-dev-mysql-slow-log-ap-chengdu", props.resolveTopicName("mysql-slow"));
    }

    @Test
    void skillBlockMentionsMysqlSlowTopic() {
        ClsProperties props = new ClsProperties();
        props.setRegion("ap-chengdu");
        props.setLogsetId("bd870dc4-1b4d-4b60-b377-c7d1e297e9f4");
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("mysql-slow", "9cddac58-51e9-442a-acc9-3f9ac1b24380");
        props.setTopics(topics);

        String block = props.buildTopicsPromptBlock();
        assertTrue(block.contains("mysql-slow"));
        assertTrue(block.contains("tjxt-dev-mysql-slow-log-ap-chengdu"));
        assertTrue(block.contains("9cddac58-51e9-442a-acc9-3f9ac1b24380"));
        assertTrue(block.contains("/data/tjxt/logs/mysql/**/slow.log"));
    }
}
