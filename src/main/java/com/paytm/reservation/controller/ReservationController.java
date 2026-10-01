package com.paytm.reservation.controller;

import com.paytm.reservation.dto.CancelReservationResponse;
import com.paytm.reservation.exception.UnauthorizedException;
import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelReservationResponse> cancelReservation(@PathVariable("id") UUID reservationId) {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("Bearer token required to cancel reservation");
        }

        CancelReservationResponse response = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(response);
    }
}
