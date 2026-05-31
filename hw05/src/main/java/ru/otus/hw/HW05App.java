package ru.otus.hw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.otus.hw.config.EnvLoader;

@SpringBootApplication
public class HW05App {

    public static void main(String[] args) {
        EnvLoader.loadEnvFile();
        SpringApplication.run(HW05App.class, args);
    }
}