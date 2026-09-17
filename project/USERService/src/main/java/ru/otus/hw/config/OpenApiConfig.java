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
        title = "User Management API",
        version = "v1",
        description = "ВНИМАНИЕ: авторизация через форму выполняется через запущенный AUTHService " +
                "(порт 8006). Без запущенного AUTHService кнопка Authorize и \"Try it out\" " +
                "для защищённых методов работать не будут.\n" +
                "API управления пользователями: просмотр и обновление профиля текущего пользователя " +
                "(контакты и адреса доставки)",
        contact = @Contact(name = "Alexander Nikolaev", email = "alexander.nikolaev@gmail.com"),
        license = @License(name = "Apache 2.0", url = "https://springdoc.org")
    ),
    servers = {
        @Server(url = "http://localhost:8000", description = "Development Server")
    }
)
@SecurityScheme(
    name = "basicAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "basic",
    description = "HTTP Basic: email and password of a real user, validated by the running AUTHService " +
            "(POST http://localhost:8006/api/v1/auth/login). " +
            "JWT Bearer remains supported by the API itself via the Authorization header but is not " +
            "exposed as a Swagger security scheme on purpose"
)
public class OpenApiConfig {
}
