package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;

import java.util.UUID;

public interface RedemptionService {
    RedeemPointsResponse redeemPoints(UUID customerId, String idempotencyKey, RedeemPointsRequest request);
}
