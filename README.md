# simple-proxy-pass

## Build

```
mvn clean package
```


## Launch

```
java -Dlog4j.configuration=file:"src/test/resources/log4j.properties" -jar target/simple-proxy-pass-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Or with code

```
Proxy myProxy = new Proxy(8085);
myProxy.listen();	
```

## Output

```
Waiting for client on port 8085...
```