package com.skyvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Punkt wejścia modułu Server.
 *
 * Uruchamianie: SkyVault.ps1 w katalogu głównym projektu.
 * Adres po starcie: http://localhost:8080
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
