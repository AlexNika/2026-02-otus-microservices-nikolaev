package ru.otus.hw.repository;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.User;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph("user-profile-graph")
    List<User> findByIdIn(Collection<Long> ids);

    @NullMarked
    @EntityGraph("user-with-profile-graph")
    Optional<User> findById(Long id);

    @EntityGraph("user-role-graph")
    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%'))")
    List<User> findByEmailContaining(@Param("email") String email);

    @EntityGraph("user-role-graph")
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.enabled = true")
    Optional<User> findByEmailAndEnabledTrue(String email);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.email = :email")
    boolean existsByEmail(String email);

    void deleteByEmail(String email);

    @NullMarked
    @EntityGraph("user-profile-graph")
    Page<User> findAll(Pageable pageable);

    @EntityGraph("user-role-graph")
    @Query("SELECT u FROM User u")
    List<User> findAllWithRoles();

    @Query("SELECT u FROM User u")
    Slice<User> findUserSlice(Pageable pageable);

    @Query("SELECT u FROM User u")
    @EntityGraph("user-role-graph")
    Slice<User> findUserSliceWithRoles(Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END " +
            "FROM User u LEFT JOIN u.profile p WHERE p.userName = :username")
    boolean existsByUserName(String username);

    @EntityGraph("user-profile-graph")
    Optional<User> findByProfileUserName(String userName);

    boolean existsByProfileUserName(String userName);
}