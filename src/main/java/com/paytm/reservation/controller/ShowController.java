package com.paytm.reservation.controller;

import com.paytm.reservation.dto.*;
import com.paytm.reservation.exception.UnauthorizedException;
import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ReservationService reservationService;

    public ShowController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ShowDetailResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowDetailResponse createdShow = reservationService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdShow);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShowDetailResponse> getShow(@PathVariable("id") UUID showId) {
        ShowDetailResponse show = reservationService.getShowDetails(showId);
        return ResponseEntity.ok(show);
    }

    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable("id") UUID showId,
            @Valid @RequestBody ReserveSeatsRequest request) {

        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("Bearer token required to reserve seats");
        }

        ReservationResponse response = reservationService.reserveSeats(showId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
