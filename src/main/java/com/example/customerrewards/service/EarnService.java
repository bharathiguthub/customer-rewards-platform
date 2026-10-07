package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.EarnPointsResponse;

import java.util.UUID;

public interface EarnService {
    EarnPointsResponse earnPoints(UUID customerId, EarnPointsRequest request);
}
