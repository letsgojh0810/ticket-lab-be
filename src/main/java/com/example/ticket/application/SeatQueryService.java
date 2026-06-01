package com.example.ticket.application;

import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.interfaces.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 좌석 조회 서비스
 *
 * DB를 상태의 원천으로 사용합니다.
 * Redis는 분산 락 용도로만 사용하며, 좌석 상태는 Seat.status 컬럼에서 읽습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatQueryService {

    private final SeatRepository seatRepository;

    /**
     * 전체 좌석 목록 조회 — DB의 status 컬럼이 기준
     */
    public List<SeatResponse> getAllSeats() {
        return seatRepository.findAll().stream()
                .map(seat -> SeatResponse.of(seat, seat.getStatus().name()))
                .toList();
    }

    /**
     * 단일 좌석 조회
     *
     * @param seatId 좌석 ID
     */
    public SeatResponse getSeat(Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다: " + seatId));
        return SeatResponse.of(seat, seat.getStatus().name());
    }

    /**
     * 예약 가능한 좌석만 조회
     */
    public List<SeatResponse> getAvailableSeats() {
        return getAllSeats().stream()
                .filter(s -> SeatStatus.AVAILABLE.name().equals(s.getStatus()))
                .toList();
    }
}
