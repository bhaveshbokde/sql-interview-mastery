package com.bhavesh.sqlmastery.curriculum;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads the curriculum on every startup.
 *
 * <p>Ordered after {@code DatasetBootstrap}, which is not arbitrary: loading
 * the content involves no queries against the learning data, but a developer
 * reading the log should see the dataset appear before the lessons that
 * describe it.
 */
@Component
@Order(20)
// Not under the "record" profile: the loader refuses content whose statements
// have no recorded output, which is exactly the state the recorder exists to
// fix. Running both would make the first recording impossible.
@Profile("!record")
public class CurriculumBootstrap implements ApplicationRunner {

    private final CurriculumLoader loader;

    public CurriculumBootstrap(CurriculumLoader loader) {
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) {
        loader.load();
    }
}
