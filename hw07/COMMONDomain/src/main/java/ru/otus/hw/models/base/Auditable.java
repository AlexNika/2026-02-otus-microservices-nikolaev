package ru.otus.hw.models.base;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

public interface Auditable {

    @CreatedDate
    LocalDateTime getCreated();
    void setCreated(LocalDateTime created);

    @LastModifiedDate
    LocalDateTime getUpdated();
    void setUpdated(LocalDateTime updated);

    @CreatedBy
    String getCreatedBy();
    void setCreatedBy(String createdBy);

    @LastModifiedBy
    String getLastModifiedBy();
    void setLastModifiedBy(String lastModifiedBy);
}
