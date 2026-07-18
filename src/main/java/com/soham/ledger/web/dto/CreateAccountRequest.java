package com.soham.ledger.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateAccountRequest(

        @NotBlank
        String ownerName,

        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
        String currency,

        @NotNull
        @DecimalMin(value = "0.0", message = "starting balance cannot be negative")
        BigDecimal startingBalance
) {
}
