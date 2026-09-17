package ru.otus.hw.models;

/**
 * Статус биллинг-аккаунта пользователя (событийная хореография USER → BILLING).
 *
 * <ul>
 *   <li>{@code PENDING} - пользователь создан, биллинг-аккаунт ещё не создан; логин разрешён;</li>
 *   <li>{@code ACTIVE} - аккаунт создан, полная функциональность;</li>
 *   <li>{@code BLOCKED} - аккаунт не создан после тайм-аута; логин запрещён
 *       ({@code locked=true} → {@code LockedException} → 401).</li>
 * </ul>
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    BLOCKED
}
