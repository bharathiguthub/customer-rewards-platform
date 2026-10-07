package com.example.customerrewards.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter to clean up MDC after each request.
 * Ensures correlation IDs and other MDC data don't leak between requests.
 */
@Component
public class MdcCleanupFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
