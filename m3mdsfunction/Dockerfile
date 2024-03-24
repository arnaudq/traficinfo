#FROM fnproject/fn-java-fdk-build:1.0.111 as build-stage
#WORKDIR /function
#ENV MAVEN_OPTS -Dhttp.proxyHost=www-proxy-ams.nl.oracle.com -Dhttp.proxyPort=80 -Dhttps.proxyHost=www-proxy-ams.nl.oracle.com -Dhttps.proxyPort=80 -Dhttp.nonProxyHosts=artifacthub.oraclecorp.com -Dmaven.repo.local=/usr/share/maven/ref/repository
#ADD pom.xml /function/pom.xml
#ADD settings.xml /function/settings.xml
#RUN ["mvn", "package", "dependency:copy-dependencies", "--settings", "settings.xml", "-Dcheckstyle.skip", "-DincludeScope=runtime", "-DskipTests=true", "-Dmdep.prependGroupId=true", "-DoutputDirectory=target", "--fail-never"]
#ADD src /function/src
#RUN ["mvn", "package", "--settings", "settings.xml", "-Dcheckstyle.skip"]
FROM fnproject/fn-java-fdk:jre17-1.0.185
WORKDIR /function
#COPY --from=build-stage /function/target/*.jar /function/app/
COPY ./target/m3mdsfunction-1.0-SNAPSHOT-jar-with-dependencies.jar /function/app/
CMD ["org.traficinfo.m3m.dsfunction.DataSourceFunction::handleRequest"]
