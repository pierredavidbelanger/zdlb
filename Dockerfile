FROM openjdk:8

RUN mkdir -p /usr/local/lib/app

COPY target/zdlb-*.jar /usr/local/lib/app/app.jar

CMD ["/usr/bin/java", "-jar", "/usr/local/lib/app/app.jar"]