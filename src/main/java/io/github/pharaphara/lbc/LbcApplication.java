package io.github.pharaphara.lbc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** LBC, le bon container. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LbcApplication {

    public static void main(String[] args) {
        SpringApplication.run(LbcApplication.class, args);
    }
}
