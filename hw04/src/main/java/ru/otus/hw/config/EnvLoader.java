package ru.otus.hw.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

@Slf4j
@Component
public class EnvLoader {

    /**
     * Метод загружает переменные из .env файла в System Properties
     */
    public static void loadEnvFile() {
        try {
            File envFile = new File(".env");
            if (!envFile.exists()) {
                ClassPathResource resource = new ClassPathResource(".env");
                if (resource.exists()) {
                    envFile = resource.getFile();
                }
            }
            if (envFile.exists()) {
                Properties props = new Properties();
                props.load(new FileInputStream(envFile));
                for (String name : props.stringPropertyNames()) {
                    String value = props.getProperty(name);
                    if (System.getProperty(name) == null) {
                        System.setProperty(name, value);
                    }
                }
                log.info("Loaded .env file successfully from: {}", envFile.getAbsolutePath());
            } else {
                log.warn(".env file not found, using system environment variables");
            }
        } catch (Exception e) {
            log.error("Error loading .env file: {}", e.getMessage());
        }
    }
}