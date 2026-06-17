package ru.otus.hw.models;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static ru.otus.hw.models.User.PASSWORD_REGEXP_PATTERN;

public class UserTest {

    @Test
    void passwordPattern_validatesCorrectly() {
        var validPasswords = List.of("Password1!", "Test1234@", "aA1@bcdefg");
        var invalidPasswords = List.of("password", "PASSWORD1!", "Pass1", "Pass 1!");

        for (String p : validPasswords) {
            assertTrue(p.matches(PASSWORD_REGEXP_PATTERN), "Should be valid: " + p);
        }
        for (String p : invalidPasswords) {
            assertFalse(p.matches(PASSWORD_REGEXP_PATTERN), "Should be invalid: " + p);
        }
    }
}
