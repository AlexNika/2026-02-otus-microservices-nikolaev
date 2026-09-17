package ru.otus.hw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.otus.hw.config.EnvLoader;

@SpringBootApplication
public class AuthServiceApplication {

    static void main(String[] args) {
        EnvLoader.loadEnvFile();
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
