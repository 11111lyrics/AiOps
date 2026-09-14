package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(excludeName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration"
})
@EnableScheduling
@ConfigurationPropertiesScan
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}