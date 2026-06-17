package ru.otus.hw.config.properties;

public interface JwtSecretKeyConfig {

    long getJwtExpirationTime();

    String getJwtSecretKey();
}
