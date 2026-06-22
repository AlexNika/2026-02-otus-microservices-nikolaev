package ru.otus.hw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.otus.hw.config.EnvLoader;

@SpringBootApplication
public class HW06App {

    public static void main(String[] args) {
        EnvLoader.loadEnvFile();
        SpringApplication.run(HW06App.class, args);
    }
}