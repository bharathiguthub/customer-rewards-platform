package com.example.customerrewards.dto.response;

import java.util.List;
import java.util.UUID;

public record TransactionHistoryResponse(
    UUID customerId,
    List<TransactionResponse> transactions,
    int page,
    int size,
    long totalElements,
    int totalPages
) {}
