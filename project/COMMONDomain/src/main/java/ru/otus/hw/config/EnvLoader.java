package ru.otus.hw.config;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
            File envFile = findEnvFile();
            if (envFile == null) {
                log.warn(".env file not found, using system environment variables");
                return;
            }

            loadPropertiesFromFile(envFile);
            log.info("Loaded .env file successfully from: {}", envFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Error loading .env file: {}", e.getMessage(), e);
        }
    }

    private static @Nullable File findEnvFile() throws Exception {
        File envFile = new File(".env");
        if (envFile.exists()) {
            return envFile;
        }

        ClassPathResource resource = new ClassPathResource(".env");
        if (!resource.exists()) {
            return null;
        }
        return resource.getFile();
    }

    private static void loadPropertiesFromFile(File envFile) throws Exception {
        Properties props = new Properties();
        try (var inputStream = new FileInputStream(envFile)) {
            props.load(inputStream);
        }

        props.stringPropertyNames().forEach(name -> {
            if (System.getProperty(name) == null) {
                System.setProperty(name, props.getProperty(name));
            }
        });
    }
}
