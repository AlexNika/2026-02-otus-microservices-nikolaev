package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.UserProfile;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    
    @Query("SELECT p FROM UserProfile p WHERE p.user.id = :userId")
    Optional<UserProfile> findByUserId(@Param("userId") Long userId);
    
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM UserProfile p WHERE p.user.id = :userId")
    boolean existsByUserId(@Param("userId") Long userId);
}
