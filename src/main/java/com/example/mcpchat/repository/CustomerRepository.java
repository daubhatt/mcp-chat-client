package com.example.mcpchat.repository;

import com.example.mcpchat.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByCustomerId(String customerId);

    @Modifying
    @Query("UPDATE Customer c SET c.lastActiveAt = :timestamp WHERE c.customerId = :customerId")
    void updateLastActiveTime(@Param("customerId") String customerId, @Param("timestamp") LocalDateTime timestamp);
}