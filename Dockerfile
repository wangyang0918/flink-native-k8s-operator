FROM openjdk:8-jdk-slim-buster

COPY ./k8s-operator/target/k8s-operator-1.0-SNAPSHOT.jar /

CMD ["java", "-jar", "/k8s-operator-1.0-SNAPSHOT.jar"]