package com.bhavesh.sqlmastery.dataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Populates the learning dataset the first time the application starts against
 * an empty database.
 *
 * <p>It calls exactly the same method as the Reset button, so there is no
 * separate "initial data" path that could drift from the one a learner uses.
 *
 * <p>It only acts when the dataset is empty. Reseeding on every boot would
 * discard nothing of value — the data is disposable by design — but it would
 * also truncate the tables underneath anyone mid-query during a rolling
 * restart, for no benefit.
 */
@Component
@Order(10)
public class DatasetBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetBootstrap.class);

    private final DatasetResetService resetService;

    public DatasetBootstrap(DatasetResetService resetService) {
        this.resetService = resetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (resetService.isEmpty()) {
            log.info("Learning dataset is empty; seeding it.");
            DatasetResetService.ResetSummary summary = resetService.reset(true);
            log.info("Seeded {} rows across {} tables.", summary.totalRows(), summary.tables());
        } else {
            log.info("Learning dataset already populated; leaving it alone.");
        }
    }
}
