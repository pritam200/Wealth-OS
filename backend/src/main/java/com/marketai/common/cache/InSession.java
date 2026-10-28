package com.marketai.common.cache;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work off a request thread with an entity manager bound for its duration, exactly as
 * open-in-view does for a request, so code written for a request behaves the same in the
 * background (lazy associations load instead of failing).
 */
public final class InSession {

    private InSession() {}

    public static void run(EntityManagerFactory emf, Runnable job) {
        if (emf == null || TransactionSynchronizationManager.hasResource(emf)) { job.run(); return; }
        EntityManager em = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        try {
            job.run();
        } finally {
            TransactionSynchronizationManager.unbindResource(emf);
            EntityManagerFactoryUtils.closeEntityManager(em);
        }
    }
}
