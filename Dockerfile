FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY src/ src/
COPY index.html .
RUN mkdir -p out && javac -d out src/org/example/NodoWeb.java
RUN cp index.html out/

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/out/ .
EXPOSE 8080
CMD ["java", "-cp", ".", "org.example.NodoWeb"]
