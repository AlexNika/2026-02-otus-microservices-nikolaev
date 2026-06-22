package ru.otus.hw.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ValidationMessages {
    ENTITY_NOT_FOUND_MESSAGE("%s with id %d not found"),
    ILLEGAL_ARGUMENT_MESSAGE("%s id list can't be null or empty"),
    ENTITY_LIST_NOT_FOUND_MESSAGE("One or all %ss with ids %s not found");

    private final String message;

    public String getMessage(Object... args) {
        if ((args == null) || (args.length == 0)) {
            return message;
        }
        return message.formatted(args);
    }
}
