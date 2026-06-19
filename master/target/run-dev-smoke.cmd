@echo off
set SERVER_PORT=18085
set SPRING_DEVTOOLS_RESTART_ENABLED=false
call C:\java\maven\apache-maven-3.9.9\bin\mvn.cmd -q spring-boot:run
