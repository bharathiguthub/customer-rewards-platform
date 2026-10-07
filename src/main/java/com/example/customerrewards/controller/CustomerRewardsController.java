package com.example.customerrewards.controller;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dto.response.TransactionHistoryResponse;
import com.example.customerrewards.service.CustomerService;
import com.example.customerrewards.service.EarnService;
import com.example.customerrewards.service.RedemptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customer Rewards", description = "Manage customer loyalty accounts and reward point transactions")
public class CustomerRewardsController {

    private static final Logger log = LoggerFactory.getLogger(CustomerRewardsController.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final CustomerService customerService;
    private final RedemptionService redemptionService;
    private final EarnService earnService;

    public CustomerRewardsController(CustomerService customerService, RedemptionService redemptionService,
                                     EarnService earnService) {
        this.customerService = customerService;
        this.redemptionService = redemptionService;
        this.earnService = earnService;
    }

    @PostMapping
    @Operation(summary = "Create a new customer", description = "Register a new customer in the rewards program")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Customer created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request data"),
        @ApiResponse(responseCode = "409", description = "Duplicate email")
    })
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        String id = initializeCorrelationId(correlationId, response);
        log.info("Creating customer with email: {}", request.email());
        
        CustomerResponse result = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer details", description = "Retrieve customer profile information")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer found"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponse> getCustomer(
            @PathVariable @Parameter(description = "Customer ID") UUID customerId,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        initializeCorrelationId(correlationId, response);
        log.info("Fetching customer: {}", customerId);
        
        CustomerResponse result = customerService.getCustomer(customerId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{customerId}/rewards")
    @Operation(summary = "Get reward balance", description = "Check current reward points balance")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<RewardBalanceResponse> getRewardBalance(
            @PathVariable @Parameter(description = "Customer ID") UUID customerId,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        initializeCorrelationId(correlationId, response);
        log.info("Fetching reward balance for customer: {}", customerId);
        
        RewardBalanceResponse result = customerService.getRewardBalance(customerId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{customerId}/rewards/redeem")
    @Operation(summary = "Redeem reward points", description = "Redeem customer reward points. Requires Idempotency-Key header.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Points redeemed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or missing/invalid Idempotency-Key"),
        @ApiResponse(responseCode = "404", description = "Customer not found"),
        @ApiResponse(responseCode = "409", description = "Insufficient balance or concurrency conflict")
    })
    public ResponseEntity<RedeemPointsResponse> redeemPoints(
            @PathVariable @Parameter(description = "Customer ID") UUID customerId,
            @Valid @RequestBody RedeemPointsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        initializeCorrelationId(correlationId, response);
        
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Redemption request missing or invalid Idempotency-Key for customer: {}", customerId);
            throw new IllegalArgumentException("Idempotency-Key header is required and must not be blank");
        }
        
        log.info("Redeeming points for customer: {} with idempotency key: {}", customerId, idempotencyKey);
        
        RedeemPointsResponse result = redemptionService.redeemPoints(customerId, idempotencyKey, request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{customerId}/rewards/transactions")
    @Operation(summary = "Get transaction history", description = "Retrieve paginated reward transaction history")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction history retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<TransactionHistoryResponse> getTransactionHistory(
            @PathVariable @Parameter(description = "Customer ID") UUID customerId,
            @RequestParam(value = "page", defaultValue = "0") @Parameter(description = "Page number (0-based)") int page,
            @RequestParam(value = "size", defaultValue = "20") @Parameter(description = "Page size") int size,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        initializeCorrelationId(correlationId, response);
        log.info("Fetching transaction history for customer: {} (page: {}, size: {})", customerId, page, size);
        
        TransactionHistoryResponse result = customerService.getTransactionHistory(customerId, page, size);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{customerId}/rewards/earn")
    @Operation(summary = "Earn reward points", description = "Add reward points to customer account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Points earned successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request (invalid points or overflow)"),
        @ApiResponse(responseCode = "404", description = "Customer not found"),
        @ApiResponse(responseCode = "409", description = "Concurrency conflict")
    })
    public ResponseEntity<EarnPointsResponse> earnPoints(
            @PathVariable @Parameter(description = "Customer ID") UUID customerId,
            @Valid @RequestBody EarnPointsRequest request,
            @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId,
            HttpServletResponse response) {
        
        initializeCorrelationId(correlationId, response);
        log.info("Earning points for customer: {} with points: {}", customerId, request.points());
        
        EarnPointsResponse result = earnService.earnPoints(customerId, request);
        return ResponseEntity.ok(result);
    }

    private String initializeCorrelationId(String correlationId, HttpServletResponse response) {
        String id = correlationId;
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(CORRELATION_ID_MDC_KEY, id);
        response.addHeader(CORRELATION_ID_HEADER, id);
        return id;
    }
}
