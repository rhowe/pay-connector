FROM java:8-jre

ENV DEBIAN_FRONTEND noninteractive
CMD apt-get update && apt-get -y install netcat

ENV JAVA_HOME /usr/lib/jvm/java-8-*/
ENV PORT 8080
ENV ADMIN_PORT 8081

EXPOSE 8080 8081
WORKDIR /app

ADD target/*.yaml /app/
ADD target/pay-*-allinone.jar /app/

CMD until nc -zv postgres 5432; do sleep 1; done && java -jar *-allinone.jar db migrate *.yaml && java -jar *-allinone.jar server *.yaml
