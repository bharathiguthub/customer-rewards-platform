package com.example.customerrewards.exception;

public class DuplicateCustomerEmailException extends RuntimeException {
    public DuplicateCustomerEmailException(String email) {
        super("A customer with email '" + email + "' already exists");
    }
}
