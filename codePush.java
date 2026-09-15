Yes. Below is a complete reference implementation you can turn into an internal JFrog-hosted Spring Boot starter.

I am targeting Java 21 + Spring Boot 4.1.1 here because that is the current stable Spring Boot line in the official documentation. Spring Boot 4.1.1 requires Java 17+, so Java 21 is fully suitable.

The implementation uses Spring Boot's native SSL Bundle abstraction rather than manually constructing SSLContext, KeyManagerFactory, and TrustManagerFactory. Spring Boot supports JKS/PKCS12 and PEM bundles and exposes them to RestClient and WebClient.

1. What we are going to build

We'll create this repository:

mtls-platform/
│
├── mtls-spring-boot-starter/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/myorg/platform/mtls/
│       │   ├── MtlsProperties.java
│       │   ├── MtlsClientFactory.java
│       │   ├── MtlsAutoConfiguration.java
│       │   ├── server/
│       │   │   ├── MtlsServerProperties.java
│       │   │   └── MtlsServerSecurityAutoConfiguration.java
│       │   └── model/
│       │       └── MtlsClientIdentity.java
│       │
│       └── resources/META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│
└── mtls-demo-dapi/
    ├── pom.xml
    └── src/main/
        ├── java/com/myorg/customer/
        │   ├── CustomerDapiApplication.java
        │   ├── config/SecurityConfig.java
        │   └── controller/CustomerController.java
        └── resources/
            └── application.yaml

The finished request path will be:

Consumer service
      |
      | HTTPS + client certificate
      |
      v
   DAPI
      |
      +---- mTLS handshake
      |
      +---- certificate validation
      |
      +---- certificate identity
      |
      +---- authorization
      |
      v
 Customer API

And your application code becomes:

RestClient client =
        mtlsClientFactory.restClient("https://customer-dapi:8443");

No hand-written SSLContext.

2. Maven parent project

Create:

mtls-platform/pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
           http://maven.apache.org/POM/4.0.0
           https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <groupId>com.myorg.platform</groupId>
    <artifactId>mtls-platform</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <name>MyOrg mTLS Platform</name>

    <modules>
        <module>mtls-spring-boot-starter</module>
        <module>mtls-demo-dapi</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <spring-boot.version>4.1.1</spring-boot.version>
    </properties>

    <dependencyManagement>
        <dependencies>

            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>

                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <release>${java.version}</release>
                    </configuration>
                </plugin>

            </plugins>
        </pluginManagement>
    </build>

</project>
3. Starter pom.xml

Create:

mtls-spring-boot-starter/pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
           http://maven.apache.org/POM/4.0.0
           https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.myorg.platform</groupId>
        <artifactId>mtls-platform</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>mtls-spring-boot-starter</artifactId>
    <packaging>jar</packaging>

    <name>MyOrg mTLS Spring Boot Starter</name>

    <dependencies>

        <!-- AutoConfiguration, SSL Bundles, properties -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <!-- RestClient -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>

        <!-- WebClient -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Spring Security X509 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>

</project>
4. mTLS client properties

Create:

MtlsProperties.java
package com.myorg.platform.mtls;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "myorg.mtls")
public class MtlsProperties {

    /**
     * Enables the MyOrg mTLS starter.
     */
    private boolean enabled = false;

    /**
     * Default Spring Boot SSL bundle used by clients.
     */
    private String bundle = "default";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBundle() {
        return bundle;
    }

    public void setBundle(String bundle) {
        this.bundle = bundle;
    }
}
5. mTLS client factory

This is the class application developers will use.

Create:

MtlsClientFactory.java
package com.myorg.platform.mtls;

import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.boot.webclient.autoconfigure.WebClientSsl;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

public class MtlsClientFactory {

    private final String bundleName;

    private final RestClientSsl restClientSsl;

    private final WebClientSsl webClientSsl;

    public MtlsClientFactory(
            String bundleName,
            RestClientSsl restClientSsl,
            WebClientSsl webClientSsl) {

        this.bundleName = bundleName;
        this.restClientSsl = restClientSsl;
        this.webClientSsl = webClientSsl;
    }

    public RestClient restClient(String baseUrl) {

        return RestClient.builder()
                .baseUrl(baseUrl)
                .apply(restClientSsl.fromBundle(bundleName))
                .build();
    }

    public WebClient webClient(String baseUrl) {

        return WebClient.builder()
                .baseUrl(baseUrl)
                .apply(webClientSsl.fromBundle(bundleName))
                .build();
    }
}

                           Spring Boot's current API exposes RestClientSsl.fromBundle(...) specifically for applying an SSL bundle to a RestClient.Builder.

The corresponding WebClientSsl API is also provided by Spring Boot.

6. Auto-configuration

Create:

MtlsAutoConfiguration.java
package com.myorg.platform.mtls;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.boot.webclient.autoconfigure.WebClientSsl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MtlsProperties.class)
@ConditionalOnProperty(
        prefix = "myorg.mtls",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnClass({
        RestClientSsl.class,
        WebClientSsl.class
})
public class MtlsAutoConfiguration {

    @Bean
    public MtlsClientFactory mtlsClientFactory(
            MtlsProperties properties,
            RestClientSsl restClientSsl,
            WebClientSsl webClientSsl) {

        return new MtlsClientFactory(
                properties.getBundle(),
                restClientSsl,
                webClientSsl
        );
    }
}
7. Register the auto-configuration

Create:

mtls-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

Contents:

com.myorg.platform.mtls.MtlsAutoConfiguration

That's what causes Spring Boot to automatically discover your starter configuration.

So an application developer doesn't need:

@Configuration
@EnableMtls
@ComponentScan(...)

etc.

8. Server-side properties

Now let's create reusable server configuration.

Create:

server/MtlsServerProperties.java
package com.myorg.platform.mtls.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "myorg.mtls.server")
public class MtlsServerProperties {

    private boolean enabled = false;

    private List<Client> clients = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Client> getClients() {
        return clients;
    }

    public void setClients(List<Client> clients) {
        this.clients = clients;
    }

    public static class Client {

        private String name;

        private String commonName;

        private List<String> authorities = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCommonName() {
            return commonName;
        }

        public void setCommonName(String commonName) {
            this.commonName = commonName;
        }

        public List<String> getAuthorities() {
            return authorities;
        }

        public void setAuthorities(List<String> authorities) {
            this.authorities = authorities;
        }
    }
}
9. Server-side X.509 authorization

Spring Security's X.509 support extracts the certificate identity and integrates it with Spring Security authentication/authorization.

Create:

server/MtlsServerSecurityAutoConfiguration.java
package com.myorg.platform.mtls.server;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(MtlsServerProperties.class)
@ConditionalOnProperty(
        prefix = "myorg.mtls.server",
        name = "enabled",
        havingValue = "true"
)
public class MtlsServerSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UserDetailsService mtlsUserDetailsService(
            MtlsServerProperties properties) {

        List<UserDetails> users = new ArrayList<>();

        for (MtlsServerProperties.Client client :
                properties.getClients()) {

            String[] authorities =
                    client.getAuthorities().toArray(new String[0]);

            UserDetails user = User
                    .withUsername(client.getCommonName())
                    .password("{noop}not-used")
                    .authorities(authorities)
                    .build();

            users.add(user);
        }

        return new InMemoryUserDetailsManager(users);
    }
}

This is intentionally simple.

For your production organization, I'd eventually replace this in-memory mapping with something such as:

Certificate SAN
      ↓
Service Registry
      ↓
Service Identity
      ↓
Permissions

But this implementation gives you a working baseline.

10. Add the server auto configuration to the starter

Change:

org.springframework.boot.autoconfigure.AutoConfiguration.imports

to:

com.myorg.platform.mtls.MtlsAutoConfiguration
com.myorg.platform.mtls.server.MtlsServerSecurityAutoConfiguration
11. DAPI project

Now create:

mtls-demo-dapi/pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
           http://maven.apache.org/POM/4.0.0
           https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.myorg.platform</groupId>
        <artifactId>mtls-platform</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>mtls-demo-dapi</artifactId>

    <dependencies>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <dependency>
            <groupId>com.myorg.platform</groupId>
            <artifactId>mtls-spring-boot-starter</artifactId>
            <version>1.0.0</version>
        </dependency>

    </dependencies>

    <build>
        <plugins>

            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

        </plugins>
    </build>

</project>
12. Main class

Create:

CustomerDapiApplication.java
package com.myorg.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CustomerDapiApplication {

    public static void main(String[] args) {

        SpringApplication.run(
                CustomerDapiApplication.class,
                args
        );
    }
}
13. DAPI SecurityConfig

Create:

config/SecurityConfig.java
package com.myorg.customer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())

            .x509(x509 -> x509
                .subjectPrincipalRegex(
                    "CN=(.*?)(?:,|$)"
                )
            )

            .authorizeHttpRequests(authorize -> authorize

                .requestMatchers(
                    "/actuator/health"
                ).permitAll()

                .requestMatchers(
                    "/api/v1/customers/**"
                ).hasAuthority("CUSTOMER_READ")

                .requestMatchers(
                    "/api/v1/customer-change-requests"
                ).hasAuthority("CUSTOMER_MAKER")

                .requestMatchers(
                    "/api/v1/customer-change-requests/**/approve",
                    "/api/v1/customer-change-requests/**/reject"
                ).hasAuthority("CUSTOMER_CHECKER")

                .anyRequest()
                .authenticated()
            );

        return http.build();
    }
}

The .x509() filter lets Spring Security authenticate the client certificate after TLS has successfully established the client certificate identity.

14. Customer controller

Create:

controller/CustomerController.java
package com.myorg.customer.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    @GetMapping("/{customerId}")
    public Map<String, Object> getCustomer(
            @PathVariable Long customerId,
            Authentication authentication) {

        return Map.of(
                "customerId", customerId,
                "name", "Demo Customer",
                "email", "customer@example.com",
                "calledBy", authentication.getName()
        );
    }
}
15. DAPI application.yaml

This is the important part.

server:
  port: 8443

  ssl:
    enabled: true
    bundle: customer-server
    client-auth: need


spring:

  ssl:

    bundle:

      jks:

        customer-server:

          key:
            alias: customer-dapi

          keystore:
            location: file:/etc/certs/customer-dapi.p12
            password: ${MTLS_KEYSTORE_PASSWORD}
            type: PKCS12

          truststore:
            location: file:/etc/certs/internal-ca.p12
            password: ${MTLS_TRUSTSTORE_PASSWORD}
            type: PKCS12


myorg:

  mtls:

    server:
      enabled: true

      clients:

        - name: customer-bff
          common-name: customer-bff
          authorities:
            - CUSTOMER_MAKER
            - CUSTOMER_READ

        - name: accounts-service
          common-name: accounts-service
          authorities:
            - CUSTOMER_READ

        - name: loans-service
          common-name: loans-service
          authorities:
            - CUSTOMER_READ

        - name: checker-service
          common-name: checker-service
          authorities:
            - CUSTOMER_CHECKER
            - CUSTOMER_READ

client-auth: need makes client certificates mandatory during the TLS handshake. Spring Boot's SSL support and Spring Security X.509 then work together: TLS validates the certificate chain while Spring Security maps the certificate identity into authorization.

16. Consumer application

Now suppose:

accounts-service

needs to call your DAPI.

Its POM needs only:

<dependency>
    <groupId>com.myorg.platform</groupId>
    <artifactId>mtls-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
17. Consumer application.yaml
myorg:

  mtls:

    enabled: true

    bundle: customer-client


spring:

  ssl:

    bundle:

      jks:

        customer-client:

          key:
            alias: accounts-service

          keystore:
            location: file:/etc/certs/accounts-service.p12
            password: ${MTLS_KEYSTORE_PASSWORD}
            type: PKCS12

          truststore:
            location: file:/etc/certs/internal-ca.p12
            password: ${MTLS_TRUSTSTORE_PASSWORD}
            type: PKCS12

That's essentially all the application has to configure.

18. Consumer service code

Create:

package com.myorg.accounts.service;

import com.myorg.platform.mtls.MtlsClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class CustomerService {

    private final RestClient customerDapi;

    public CustomerService(
            MtlsClientFactory mtlsClientFactory) {

        this.customerDapi =
                mtlsClientFactory.restClient(
                        "https://customer-dapi.internal:8443"
                );
    }

    public Map getCustomer(Long customerId) {

        return customerDapi
                .get()
                .uri(
                    "/api/v1/customers/{id}",
                    customerId
                )
                .retrieve()
                .body(Map.class);
    }
}

Notice what's missing:

SSLContext
KeyStore
TrustManager
KeyManager
HttpClient
CertificateFactory

That's the entire reason for your reusable starter.

19. WebClient version

For reactive services:

package com.myorg.accounts.service;

import com.myorg.platform.mtls.MtlsClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class ReactiveCustomerService {

    private final WebClient customerDapi;

    public ReactiveCustomerService(
            MtlsClientFactory factory) {

        this.customerDapi =
                factory.webClient(
                        "https://customer-dapi.internal:8443"
                );
    }

    public Mono<Map> getCustomer(Long customerId) {

        return customerDapi
                .get()
                .uri(
                    "/api/v1/customers/{id}",
                    customerId
                )
                .retrieve()
                .bodyToMono(Map.class);
    }
}

Spring Boot's current documentation demonstrates applying an SSL bundle to WebClient.Builder using WebClientSsl.

20. Creating certificates locally

For local testing, we can create a simple CA.

Create working directory
mkdir mtls
cd mtls
21. Create CA private key
openssl genrsa \
  -out ca.key \
  4096

Create CA certificate:

openssl req \
  -x509 \
  -new \
  -nodes \
  -key ca.key \
  -sha256 \
  -days 3650 \
  -out ca.crt \
  -subj "/CN=MyOrg Internal CA"
22. Create DAPI certificate

Generate key:

openssl genrsa \
  -out customer-dapi.key \
  2048

Create CSR:

openssl req \
  -new \
  -key customer-dapi.key \
  -out customer-dapi.csr \
  -subj "/CN=customer-dapi"

Create certificate:

openssl x509 \
  -req \
  -in customer-dapi.csr \
  -CA ca.crt \
  -CAkey ca.key \
  -CAcreateserial \
  -out customer-dapi.crt \
  -days 825 \
  -sha256
23. Create BFF/client certificate
openssl genrsa \
  -out accounts-service.key \
  2048
openssl req \
  -new \
  -key accounts-service.key \
  -out accounts-service.csr \
  -subj "/CN=accounts-service"
openssl x509 \
  -req \
  -in accounts-service.csr \
  -CA ca.crt \
  -CAkey ca.key \
  -CAcreateserial \
  -out accounts-service.crt \
  -days 825 \
  -sha256
24. Put the server certificate into PKCS12
openssl pkcs12 \
  -export \
  -out customer-dapi.p12 \
  -inkey customer-dapi.key \
  -in customer-dapi.crt \
  -certfile ca.crt \
  -name customer-dapi
25. Put the client certificate into PKCS12
openssl pkcs12 \
  -export \
  -out accounts-service.p12 \
  -inkey accounts-service.key \
  -in accounts-service.crt \
  -certfile ca.crt \
  -name accounts-service
26. Create the trust store

Import your CA:

keytool -importcert \
  -trustcacerts \
  -alias myorg-ca \
  -file ca.crt \
  -keystore internal-ca.p12 \
  -storetype PKCS12

You now have:

certificates/
│
├── ca.crt
├── ca.key
│
├── customer-dapi.crt
├── customer-dapi.key
├── customer-dapi.p12
│
├── accounts-service.crt
├── accounts-service.key
├── accounts-service.p12
│
└── internal-ca.p12
27. The actual handshake

When accounts-service executes:

customerDapi
    .get()
    .uri("/api/v1/customers/1001")
    .retrieve()

the sequence becomes:

accounts-service
       |
       | ClientHello
       |
       v
customer-dapi
       |
       | Server certificate
       |
       v
accounts-service
       |
       | validates DAPI certificate
       |
       | client certificate
       |
       v
customer-dapi
       |
       | validates accounts-service certificate
       |
       v
TLS handshake succeeds
       |
       v
HTTP GET
       |
       v
Spring Security
       |
       | CN = accounts-service
       |
       | authorities:
       | CUSTOMER_READ
       |
       v
Controller
       |
       v
200 OK

That's the complete mTLS flow.

28. Testing with curl

Once DAPI is running:

curl \
  --cert accounts-service.crt \
  --key accounts-service.key \
  --cacert ca.crt \
  https://localhost:8443/api/v1/customers/1001

You should get:

{
  "customerId": 1001,
  "name": "Demo Customer",
  "email": "customer@example.com",
  "calledBy": "accounts-service"
}
29. Without client certificate

Try:

curl \
  --cacert ca.crt \
  https://localhost:8443/api/v1/customers/1001

The connection should fail during TLS because:

client-auth: need

requires the client certificate.

That is an important property of real mTLS.

30. Authorization test

accounts-service has:

authorities:
  - CUSTOMER_READ

so:

GET /api/v1/customers/1001

works.

But:

POST /api/v1/customer-change-requests

doesn't because it requires:

CUSTOMER_MAKER

Likewise:

POST /api/v1/customer-change-requests/123/approve

requires:

CUSTOMER_CHECKER

So even if another service somehow gets a valid internal certificate, it doesn't automatically get access to every endpoint.

31. Maker-Checker code

Now let's implement the actual business workflow.

Create:

public enum ChangeRequestStatus {

    PENDING,
    APPROVED,
    REJECTED,
    FAILED
}
32. Request DTO
package com.myorg.customer.model;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateChangeRequest(
        @NotNull Long customerId,

        String reason,

        @NotNull Map<String, String> changes
) {
}
33. Change Request entity
package com.myorg.customer.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customer_change_request")
public class CustomerChangeRequest {

    @Id
    private UUID requestId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeRequestStatus status;

    @Column(nullable = false)
    private Long baseCustomerVersion;

    private String reason;

    private String checkerId;

    private Instant requestedAt;

    private Instant checkedAt;

    @OneToMany(
        mappedBy = "request",
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    private List<CustomerChangeItem> changes =
            new ArrayList<>();

    // getters/setters
}
34. Change Item
package com.myorg.customer.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "customer_change_item")
public class CustomerChangeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private CustomerChangeRequest request;

    @Column(nullable = false)
    private String fieldName;

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String proposedValue;

    // getters/setters
}
35. Customer entity
package com.myorg.customer.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private Long customerId;

    private String firstName;

    private String lastName;

    private String email;

    private String phone;

    private Long version;

    private Instant updatedAt;

    private String updatedBy;

    // getters/setters
}
36. Repositories
package com.myorg.customer.repository;

import com.myorg.customer.entity.Customer;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface CustomerRepository
        extends JpaRepository<Customer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from Customer c
        where c.customerId = :id
    """)
    Optional<Customer> findByIdForUpdate(
            @Param("id") Long id
    );
}

Change request:

package com.myorg.customer.repository;

import com.myorg.customer.entity.CustomerChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerChangeRequestRepository
        extends JpaRepository<CustomerChangeRequest, UUID> {
}
37. Maker service
package com.myorg.customer.service;

import com.myorg.customer.entity.*;
import com.myorg.customer.model.CreateChangeRequest;
import com.myorg.customer.repository.CustomerChangeRequestRepository;
import com.myorg.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerChangeRequestService {

    private final CustomerRepository customerRepository;

    private final CustomerChangeRequestRepository requestRepository;

    public CustomerChangeRequestService(
            CustomerRepository customerRepository,
            CustomerChangeRequestRepository requestRepository) {

        this.customerRepository =
                customerRepository;

        this.requestRepository =
                requestRepository;
    }

    @Transactional
    public UUID create(
            CreateChangeRequest input,
            String maker) {

        Customer customer =
                customerRepository
                        .findById(input.customerId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Customer not found"
                                ));

        CustomerChangeRequest request =
                new CustomerChangeRequest();

        request.setRequestId(UUID.randomUUID());

        request.setCustomerId(
                customer.getCustomerId()
        );

        request.setRequestedBy(maker);

        request.setStatus(
                ChangeRequestStatus.PENDING
        );

        request.setBaseCustomerVersion(
                customer.getVersion()
        );

        request.setReason(
                input.reason()
        );

        request.setRequestedAt(
                Instant.now()
        );

        for (Map.Entry<String, String> change :
                input.changes().entrySet()) {

            CustomerChangeItem item =
                    new CustomerChangeItem();

            item.setRequest(request);

            item.setFieldName(change.getKey());

            item.setProposedValue(change.getValue());

            item.setOldValue(
                    readCustomerField(
                            customer,
                            change.getKey()
                    )
            );

            request.getChanges().add(item);
        }

        requestRepository.save(request);

        return request.getRequestId();
    }

    private String readCustomerField(
            Customer customer,
            String field) {

        return switch (field) {

            case "firstName" ->
                    customer.getFirstName();

            case "lastName" ->
                    customer.getLastName();

            case "email" ->
                    customer.getEmail();

            case "phone" ->
                    customer.getPhone();

            default ->
                    throw new IllegalArgumentException(
                            "Field cannot be updated: " + field
                    );
        };
    }
}
38. Checker approval service
package com.myorg.customer.service;

import com.myorg.customer.entity.*;
import com.myorg.customer.repository.CustomerChangeRequestRepository;
import com.myorg.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CustomerApprovalService {

    private final CustomerRepository customerRepository;

    private final CustomerChangeRequestRepository requestRepository;

    public CustomerApprovalService(
            CustomerRepository customerRepository,
            CustomerChangeRequestRepository requestRepository) {

        this.customerRepository =
                customerRepository;

        this.requestRepository =
                requestRepository;
    }

    @Transactional
    public void approve(
            UUID requestId,
            String checker) {

        CustomerChangeRequest request =
                requestRepository
                        .findById(requestId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Request not found"
                                ));

        if (request.getStatus()
                != ChangeRequestStatus.PENDING) {

            throw new IllegalStateException(
                    "Request is not pending"
            );
        }

        if (request.getRequestedBy()
                .equals(checker)) {

            throw new IllegalStateException(
                    "Maker cannot approve own request"
            );
        }

        Customer customer =
                customerRepository
                        .findByIdForUpdate(
                                request.getCustomerId()
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Customer not found"
                                ));

        if (!customer.getVersion()
                .equals(
                    request.getBaseCustomerVersion()
                )) {

            throw new IllegalStateException(
                    "Customer changed since request creation"
            );
        }

        for (CustomerChangeItem item :
                request.getChanges()) {

            applyChange(
                    customer,
                    item.getFieldName(),
                    item.getProposedValue()
            );
        }

        customer.setVersion(
                customer.getVersion() + 1
        );

        customer.setUpdatedBy(checker);

        customer.setUpdatedAt(
                Instant.now()
        );

        customerRepository.save(customer);

        request.setStatus(
                ChangeRequestStatus.APPROVED
        );

        request.setCheckerId(checker);

        request.setCheckedAt(
                Instant.now()
        );

        requestRepository.save(request);
    }

    private void applyChange(
            Customer customer,
            String field,
            String value) {

        switch (field) {

            case "firstName" ->
                    customer.setFirstName(value);

            case "lastName" ->
                    customer.setLastName(value);

            case "email" ->
                    customer.setEmail(value);

            case "phone" ->
                    customer.setPhone(value);

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported field: " + field
                    );
        }
    }
}
39. Maker/checker controller
package com.myorg.customer.controller;

import com.myorg.customer.model.CreateChangeRequest;
import com.myorg.customer.service.CustomerApprovalService;
import com.myorg.customer.service.CustomerChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(
        "/api/v1/customer-change-requests"
)
public class ChangeRequestController {

    private final CustomerChangeRequestService changeRequestService;

    private final CustomerApprovalService approvalService;

    public ChangeRequestController(
            CustomerChangeRequestService changeRequestService,
            CustomerApprovalService approvalService) {

        this.changeRequestService =
                changeRequestService;

        this.approvalService =
                approvalService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody
            CreateChangeRequest request,

            Authentication authentication) {

        UUID requestId =
                changeRequestService.create(
                        request,
                        authentication.getName()
                );

        return ResponseEntity
                .status(201)
                .body(
                        Map.of(
                            "requestId",
                            requestId,
                            "status",
                            "PENDING"
                        )
                );
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable UUID requestId,
            Authentication authentication) {

        approvalService.approve(
                requestId,
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }
}
40. Complete request flow now

Your final system behaves like this.

Maker
MFE
 ↓
BFF
 ↓
POST /customer-change-requests
 ↓
mTLS
 ↓
DAPI
 ↓
CUSTOMER_MAKER
 ↓
staging/change-request tables

Main customer record:

UNCHANGED
Checker
Checker MFE
 ↓
BFF
 ↓
POST /customer-change-requests/{id}/approve
 ↓
mTLS
 ↓
DAPI
 ↓
CUSTOMER_CHECKER
 ↓
lock request
 ↓
lock customer
 ↓
verify version
 ↓
apply change
 ↓
COMMIT
41. What the internal consumers get

Each consumer gets a unique identity.

For example:

accounts-service
loans-service
cards-service
risk-service
payments-service
notifications-service
customer-search-service

Each receives its own certificate:

accounts-service.p12
loans-service.p12
cards-service.p12
...

The DAPI controls what each certificate identity can do.

Example:

- name: accounts-service
  common-name: accounts-service
  authorities:
    - CUSTOMER_READ

Therefore:

accounts-service
      |
      | GET
      v
     DAPI
      |
      ✓ CUSTOMER_READ

But:

accounts-service
      |
      | POST /approve
      v
     DAPI
      |
      X CUSTOMER_CHECKER missing
42. One production change I strongly recommend

The example uses:

CN=accounts-service

for readability.

For your enterprise implementation, I would prefer a certificate identity based on SAN/URI or another organization-defined certificate identity, and a central service identity/authorization registry.

Spring Security's X.509 support allows customization of certificate principal extraction, rather than requiring the default CN extraction.

The production model should become:

Certificate
    ↓
SAN / URI
    ↓
service identity
    ↓
authorization policy
    ↓
CUSTOMER_READ

instead of scattering certificate CNs throughout applications.

43. What the developer experience becomes

For any internal service:

Dependency
<dependency>
    <groupId>com.myorg.platform</groupId>
    <artifactId>mtls-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
YAML
myorg:
  mtls:
    enabled: true
    bundle: customer-client

spring:
  ssl:
    bundle:
      jks:
        customer-client:
          key:
            alias: my-service
          keystore:
            location: file:/etc/certs/my-service.p12
            password: ${MTLS_KEYSTORE_PASSWORD}
            type: PKCS12
          truststore:
            location: file:/etc/certs/internal-ca.p12
            password: ${MTLS_TRUSTSTORE_PASSWORD}
            type: PKCS12
Java
@Service
public class CustomerClient {

    private final RestClient client;

    public CustomerClient(
            MtlsClientFactory factory) {

        this.client = factory.restClient(
                "https://customer-dapi.internal"
        );
    }

    public Customer get(Long id) {

        return client
                .get()
                .uri("/api/v1/customers/{id}", id)
                .retrieve()
                .body(Customer.class);
    }
}

That's the API I'd expose to your organization's developers.

44. JFrog publishing

Once this builds:

mvn clean install

you can publish:

mvn deploy

to your internal Artifactory.

Your corporate Maven configuration would point to something like:

<distributionManagement>

    <repository>
        <id>myorg-releases</id>
        <url>
            https://jfrog.myorg.com/artifactory/maven-releases
        </url>
    </repository>

</distributionManagement>

Then developers only need:

<dependency>
    <groupId>com.myorg.platform</groupId>
    <artifactId>mtls-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
45. Final architecture

The complete architecture becomes:

                         ┌────────────────┐
                         │      MFE       │
                         │   Browser UI   │
                         └───────┬────────┘
                                 │
                           OIDC / JWT
                                 │
                                 ▼
                         ┌────────────────┐
                         │      BFF       │
                         │                │
                         │ mTLS client    │
                         └───────┬────────┘
                                 │
                             mTLS
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │         DAPI           │
                    │                        │
                    │ X509 authentication    │
                    │ Authorization          │
                    │ Maker/Checker          │
                    └───────┬───────┬────────┘
                            │       │
                     approved       │ pending
                            │       │
                            ▼       ▼
                       ┌────────┐ ┌─────────────┐
                       │ Main DB│ │Change DB    │
                       └────────┘ └─────────────┘


 accounts-service ────────┐
 loans-service ───────────┤
 cards-service ───────────┤
 risk-service ────────────┤
 payments-service ────────┤
 notifications-service ───┤
 customer-search ─────────┤
                           │
                         mTLS
                           │
                           ▼
                          DAPI
                           │
                     CUSTOMER_READ

The important separation is:

             SECURITY PLATFORM
                    │
        ┌───────────┴───────────┐
        │                       │
      mTLS                 Authorization
        │                       │
 certificate              roles/scopes
 identity                    policy
        │                       │
        └───────────┬───────────┘
                    ▼
                 DAPI
                    │
              Business logic
                    │
              Maker / Checker
                    │
             Customer database

This gives you a reusable platform component while keeping the customer-specific Maker-Checker logic completely outside the mTLS starter.

One caveat: the code above is a complete reference implementation, but I would not ship the in-memory CN→authority mapping to production. For an enterprise implementation, I'd replace that piece with your organization's certificate/identity registry and add certificate rotation, SAN-based identity validation, audit events, correlation IDs, metrics, and integration tests.

Also, because your existing projects have used Spring Boot 3.x, note that some package names/APIs differ between Boot 3.x and the current Boot 4.1.x line. The code above intentionally follows the current Boot 4.1.1 APIs documented by Spring.
