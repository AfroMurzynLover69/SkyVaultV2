package com.skyvault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "skyvault.storage.plan-state-file=target/test-plans.json",
        "skyvault.storage.directory=target/test-files",
        "skyvault.backup.enabled=false",
        "skyvault.backup.directory=target/test-backups",
        "skyvault.backup.catch-up=false",
        "logging.file.name=target/test.log"
})
class ServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
