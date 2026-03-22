package com.example.ticket.domain.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    void deleteBySeatIdAndUserId(Long seatId, Long userId);

    Optional<Reservation> findBySeatIdAndUserId(Long seatId, Long userId);
}
