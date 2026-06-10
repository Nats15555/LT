package com.loadtest.summarization.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
@RequiredArgsConstructor
public class DatabaseAvailabilityService {

    private final EntityManager entityManager;

    public boolean isAvailable() {
        try {
            entityManager.createNativeQuery("SELECT 1").getSingleResult();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void requireAvailable() {
        if (!isAvailable()) {
            throw new DatabaseUnavailableException("PostgreSQL is not available");
        }
    }

    public static boolean isDatabaseAccessFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DatabaseUnavailableException
                    || current instanceof DataAccessException
                    || current instanceof PersistenceException
                    || current instanceof CannotCreateTransactionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
