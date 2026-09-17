package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.models.UserAddress;

import java.util.List;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    List<UserAddress> findAllByUserId(Long userId);

    void deleteAllByUserId(Long userId);
}
