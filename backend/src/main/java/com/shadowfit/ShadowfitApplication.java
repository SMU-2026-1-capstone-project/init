package com.shadowfit;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableScheduling 은 SchedulerConfig 에 있다. 여기에도 있으면 그쪽의 on/off 스위치가
// 무력해진다 — 두 곳 중 하나만 걸려도 스케줄링은 켜지기 때문이다.
@EnableAsync
@EnableCaching
@SpringBootApplication
public class ShadowfitApplication {

	public static void main(String[] args) {
		Dotenv.configure()
				.directory("../")
				.ignoreIfMissing()
				.systemProperties()
				.load();
		SpringApplication.run(ShadowfitApplication.class, args);
	}

}
