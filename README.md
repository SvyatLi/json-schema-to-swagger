# json-schema-to-swagger
Simple (and incomplete) java app to transform json schema draft-04/draft-03/draft-06/draft-07 to swagger 2.0 json


To run it, put files in project folder, or if running as jar near jar, and execute jar
command should look like:
```
java.exe -jar .\json-schema-to-swagger-1.0-jar-with-dependencies.jar
```
I'll commit compiled jar along with code, so it should be easier to use, but it should not be a problem to build it yourself

```
mvn clean install
```
should result in jar with dependencies without additional configuration
