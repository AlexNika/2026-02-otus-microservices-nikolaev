package ru.otus.hw.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "User Management API",
        version = "v1",
        description = "API for managing users in the system",
        contact = @Contact(name = "Alexander Nikolaev", email = "alexander.nikolaev@gmail.com"),
        license = @License(name = "Apache 2.0", url = "https://springdoc.org")
    ),
    servers = {
        @Server(url = "http://localhost:8000", description = "Development Server")
    }
)
public class OpenApiConfig {
}
