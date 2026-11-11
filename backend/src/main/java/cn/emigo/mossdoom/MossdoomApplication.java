package cn.emigo.mossdoom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MossdoomApplication {
    public static void main(String[] args) { SpringApplication.run(MossdoomApplication.class, args); }
}
