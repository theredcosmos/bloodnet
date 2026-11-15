package com.bloodnet.shared;

/** A domain rule was violated, e.g. donor not yet eligible (HTTP 422). */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
