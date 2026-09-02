# 실행 스테이지 (빌드 스테이지 제거)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 한국 시간(KST) 설정 — tzdata 를 삭제하면 musl 이 TZ=Asia/Seoul 을 해석 못해
# UTC 로 폴백하므로(compose 의 TZ env 가 /etc/localtime 보다 우선) 유지해야 함
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone
ENV TZ=Asia/Seoul

# 업로드 디렉토리 생성
RUN mkdir -p /app/uploads/songs

# GitHub Actions에서 빌드된 WAR 파일을 복사
COPY target/*.war app.war

# 메모리 제한 설정
# 미적용 — exec-form ENTRYPOINT는 env 미확장. 다음 재빌드 때 제거
#ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.war"]
