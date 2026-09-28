package io.github.pharaphara.lbc.config;

import io.github.pharaphara.lbc.query.Query;
import io.github.pharaphara.lbc.store.Store;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The two pieces that only need a directory to exist. */
@Configuration
public class LbcConfig {

    @Bean
    Store store(LbcProperties props) {
        return new Store(props.data());
    }

    @Bean
    Query query(LbcProperties props) {
        // Probing writes its findings next to the searches, never into the image.
        return new Query(props.data());
    }
}
