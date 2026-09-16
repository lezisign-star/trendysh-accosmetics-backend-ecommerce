package com.nbh.backend.auth.dto;

import java.util.Set;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        UUID userId,
        String email,
        String fullName,
        String staffRole,
        Set<String> permissions,
        boolean mustChangePassword
) {}
