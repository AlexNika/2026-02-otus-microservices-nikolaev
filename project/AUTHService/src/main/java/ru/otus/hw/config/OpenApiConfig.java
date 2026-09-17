package ru.otus.hw.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Authentication API",
        version = "v1",
        description = "API for registration, login, token refresh/logout and admin management " +
                "of users (credentials) and roles",
        contact = @Contact(name = "Alexander Nikolaev", email = "alexander.nikolaev@gmail.com"),
        license = @License(name = "Apache 2.0", url = "https://springdoc.org")
    ),
    servers = {
        @Server(url = "http://localhost:8006", description = "Development Server")
    }
)
@SecurityScheme(
    name = "basicAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "basic",
    description = "HTTP Basic: email and password (dev seed admin: admin@admin.com / admin). " +
            "JWT Bearer remains supported by the API itself (see POST /api/v1/auth/login) but is not " +
            "exposed as a Swagger security scheme on purpose"
)
public class OpenApiConfig {
}
