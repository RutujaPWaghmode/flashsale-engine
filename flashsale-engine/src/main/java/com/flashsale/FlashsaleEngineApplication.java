package com.flashsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlashsaleEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlashsaleEngineApplication.class, args);
	}

}
