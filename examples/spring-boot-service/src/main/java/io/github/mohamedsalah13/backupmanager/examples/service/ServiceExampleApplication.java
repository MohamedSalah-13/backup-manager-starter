package io.github.mohamedsalah13.backupmanager.examples.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot service that backs up its own H2 database.
 *
 * <p>There is no backup code in this application beyond a listener and a controller — the
 * jobs, schedules, destinations, encryption and retention all come from
 * {@code application.yml}. That is the point of the starter.
 *
 * <p>Run it from this module's own directory, after {@code mvn install} at the repository
 * root. Running it from there with {@code -pl} fails: the {@code spring-boot} plugin prefix
 * is resolved against the root POM, which does not declare the plugin.
 * <pre>
 *   cd examples/spring-boot-service &amp;&amp; mvn spring-boot:run
 * </pre>
 */
@SpringBootApplication
public class ServiceExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceExampleApplication.class, args);
    }
}
