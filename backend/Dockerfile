# 빌드 스테이지
FROM gradle:jdk21 AS builder
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew bootJar -x test --no-daemon

# 실행 스테이지
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
EXPOSE 6565

# 컨테이너 인지형 힙 옵션을 compose 에서 주입할 자리 (#570). 고정 ENTRYPOINT 였을 때는
# -XX:MaxRAMPercentage 같은 옵션을 넣을 방법이 없어, mem_limit 을 걸어도 JVM 힙은 그 값을
# 몰랐다. 기본값은 빈 문자열 — compose 가 안 주면 지금까지와 동일하게 동작한다.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
