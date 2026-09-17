package ru.otus.hw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.otus.hw.config.EnvLoader;

@SpringBootApplication
public class DeliveryServiceApplication {

    static void main(String[] args) {
        EnvLoader.loadEnvFile();
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }

}
