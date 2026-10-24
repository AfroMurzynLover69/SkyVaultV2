package com.skyvault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "skyvault.storage.plan-state-file=target/test-plans.json")
class ServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
