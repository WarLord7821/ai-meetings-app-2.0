package com.ai.meetingnotes.repository;

import com.ai.meetingnotes.entity.Meeting;
import com.ai.meetingnotes.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, String> {

    List<Meeting> findByUserOrderByCreatedAtDesc(User user);

    Page<Meeting> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    @Query("SELECT COUNT(m) FROM Meeting m WHERE m.user = :user AND m.createdAt >= :startOfDay")
    long countByUserAndCreatedAtAfter(User user, LocalDateTime startOfDay);

    Optional<Meeting> findByIdAndUser(String id, User user);
}