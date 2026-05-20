package ru.otus.hw.models.base;

public interface Identifiable<T> {
    T getId();
    void setId(T id);
}
